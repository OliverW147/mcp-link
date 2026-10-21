package mcplink.script;

import mcplink.McpLink;
import mcplink.Config;
import mcplink.state.PacketLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * In-game JavaScript written by the agent: saved under config/mcp-link/scripts, started at launch when "Allow scripts" and
 * "Auto-start scripts" are on (otherwise every script is switched off at launch, so one that crashed the game stays off), and
 * fed game events (ticks, entities, chat, packets both ways, the HUD). Every call into a script is time-limited, and each
 * script's code runs under its own lock, so a handler on the network thread and one on the game thread never overlap.
 *
 * When no script listens for an event, its hook costs one volatile read.
 */
public final class Scripts {
    /** Returned by the packet hooks: drop the packet. */
    public static final Object CANCEL = new Object();

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    static final long HANDLER_MS = 50;
    static final long LOAD_MS = 2_000;
    static final long EVAL_MS = 5_000;
    static final java.util.Set<String> EVENTS = java.util.Set.of("tick", "entityAdded", "entityRemoved", "chat", "chatSend",
            "packetIn", "packetOut", "hud", "screen", "stop");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, Script> SCRIPTS = new ConcurrentHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("mcp-link").resolve("scripts");
    private static final Path INDEX = FabricLoader.getInstance().getConfigDir().resolve("mcp-link").resolve("scripts.json");

    /** Handlers by event, rebuilt whenever any script adds or removes one. Read without locking by the hooks. */
    private static volatile Map<String, List<Script.Handler>> byEvent = Map.of();
    private static volatile boolean ready;
    private static volatile long tick;
    private static Screen lastScreen;

    /** Entities a script made glow: entity id to colour. */
    public static final Map<Integer, Integer> GLOWING = new ConcurrentHashMap<>();
    private static final Map<Integer, Script> GLOW_OWNER = new ConcurrentHashMap<>();
    /** HUD lines a script asked for, drawn under the MCP tag. */
    record HudLine(Script owner, String text, int color, long until) {
    }

    static final Map<String, HudLine> HUD = Collections.synchronizedMap(new TreeMap<>());

    record LogLine(long seq, long time, String script, String level, String text) {
    }

    private static final ArrayDeque<LogLine> LOGS = new ArrayDeque<>();
    private static long logSeq;

    private Scripts() {
    }

    // ---------------------------------------------------------------- storage

    /**
     * Reads the saved scripts (they start once the game has finished loading). Without "Auto-start scripts" every script is
     * switched off here and saved that way, before any of them can run.
     */
    public static void load() {
        try {
            Files.createDirectories(DIR);
            Map<String, JsonObject> meta = new HashMap<>();
            boolean switchedOff = false;
            if (Files.exists(INDEX)) {
                JsonObject idx = JsonParser.parseString(Files.readString(INDEX)).getAsJsonObject();
                for (var e : idx.entrySet()) meta.put(e.getKey(), e.getValue().getAsJsonObject());
            }
            try (var files = Files.list(DIR)) {
                for (Path f : files.toList()) {
                    String fn = f.getFileName().toString();
                    if (!fn.endsWith(".js")) continue;
                    String name = fn.substring(0, fn.length() - 3);
                    if (!NAME.matcher(name).matches()) continue;
                    Script s = new Script(name, false);
                    s.source = Files.readString(f);
                    JsonObject m = meta.get(name);
                    if (m != null) {
                        if (m.has("enabled")) s.enabled = m.get("enabled").getAsBoolean();
                        if (m.has("description")) s.description = m.get("description").getAsString();
                    }
                    if (s.enabled && !Config.get().autoStartScripts) {
                        s.enabled = false;
                        switchedOff = true;
                    }
                    SCRIPTS.put(name, s);
                }
            }
            if (switchedOff) saveIndex();
        } catch (Exception e) {
            McpLink.LOG.warn("Could not read scripts from {}", DIR, e);
        }
    }

    private static synchronized void saveIndex() {
        JsonObject idx = new JsonObject();
        for (Script s : sorted()) {
            JsonObject m = new JsonObject();
            m.addProperty("enabled", s.enabled);
            m.addProperty("description", s.description);
            idx.add(s.name, m);
        }
        try {
            Files.createDirectories(INDEX.getParent());
            Files.writeString(INDEX, GSON.toJson(idx));
        } catch (Exception e) {
            McpLink.LOG.warn("Could not write {}", INDEX, e);
        }
    }

    static List<Script> sorted() {
        List<Script> out = new ArrayList<>(SCRIPTS.values());
        out.sort((a, b) -> a.name.compareTo(b.name));
        return out;
    }

    /** What the settings screen shows about a script. */
    public record Info(String name, String description, boolean enabled, boolean running, String status) {
    }

    public static List<Info> list() {
        List<Info> out = new ArrayList<>();
        for (Script s : sorted()) out.add(new Info(s.name, s.description, s.enabled, s.running, s.status));
        return out;
    }

    static Script get(String name) {
        return SCRIPTS.get(name);
    }

    public static boolean allowed() {
        return Config.get().scriptsEnabled;
    }

    // ---------------------------------------------------------------- management (client thread)

    /** Saves a script (replacing one of the same name) and starts it when scripts are allowed. Returns a start error, or null. */
    static String install(String name, String code, String description, boolean enabled) throws Exception {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Name must be 1-40 characters of a-z, 0-9, _ and -, starting with a letter or digit.");
        Script old = SCRIPTS.get(name);
        if (old != null) stop(old, "replaced");
        Script s = new Script(name, false);
        s.source = code;
        s.description = description == null ? "" : description;
        s.enabled = enabled;
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve(name + ".js"), code);
        SCRIPTS.put(name, s);
        saveIndex();
        log(s, "info", "installed");
        if (enabled && allowed() && ready) return start(s);
        return null;
    }

    static boolean remove(String name) throws Exception {
        Script s = SCRIPTS.remove(name);
        if (s == null) return false;
        stop(s, "removed");
        Files.deleteIfExists(DIR.resolve(name + ".js"));
        saveIndex();
        return true;
    }

    /** Switches one script on or off (saved). Returns a start error, or null. */
    public static String setEnabled(String name, boolean on) {
        Script s = SCRIPTS.get(name);
        if (s == null) return "no script " + name;
        s.enabled = on;
        saveIndex();
        if (!on) {
            stop(s, "switched off");
            return null;
        }
        return allowed() && ready && !s.running ? start(s) : null;
    }

    /** The "Allow scripts" setting changed. */
    public static void setAllowed(boolean on) {
        if (!ready) return;
        if (on) startAll();
        else for (Script s : SCRIPTS.values()) stop(s, "scripts are switched off in MCP Link's settings");
    }

    private static void startAll() {
        for (Script s : sorted()) {
            if (s.enabled && !s.running) start(s);
        }
    }

    static String start(Script s) {
        if (s.running) stop(s, "restarting");
        s.timeouts = 0;
        s.errorsInRow = 0;
        s.lock.lock();
        long[] prev = Engine.limit(LOAD_MS);
        Context cx = Engine.INSTANCE.enterContext();
        try {
            ImporterTopLevel scope = new ImporterTopLevel(cx);
            ScriptableObject.putProperty(scope, "game", Context.javaToJS(new ScriptApi(s), scope, cx));
            ScriptableObject.putProperty(scope, "mc", Context.javaToJS(Minecraft.getInstance(), scope, cx));
            s.scope = scope;
            s.running = true;   // game.on() works while the top level runs
            cx.evaluateString(scope, s.source, s.name + ".js", 1, null);
            s.status = "running";
            log(s, "info", "started");
            return null;
        } catch (Throwable t) {
            String why = describe(t);
            cleanup(s);
            s.status = "error: " + why;
            log(s, "error", "could not start: " + why);
            return why;
        } finally {
            Context.exit();
            Engine.restore(prev);
            s.lock.unlock();
            rebuild();
        }
    }

    static void stop(Script s, String why) {
        s.lock.lock();
        try {
            if (!s.running) {
                if (!s.status.startsWith("error")) s.status = why;
                return;
            }
            for (Script.Handler h : s.handlers) {
                if (h.event().equals("stop")) call(h.script(), h.fn(), "stop", HANDLER_MS);
            }
            cleanup(s);
            s.status = why;
            log(s, "info", "stopped: " + why);
        } finally {
            s.lock.unlock();
            rebuild();
        }
    }

    private static void cleanup(Script s) {
        s.running = false;
        s.handlers.clear();
        s.timers.clear();
        s.scope = null;
        synchronized (HUD) {
            HUD.values().removeIf(l -> l.owner() == s);
        }
        GLOW_OWNER.entrySet().removeIf(e -> {
            if (e.getValue() != s) return false;
            GLOWING.remove(e.getKey());
            return true;
        });
    }

    /** Stops a script that keeps failing, and tells the player. */
    private static void kill(Script s, String why) {
        stop(s, "stopped by MCP Link: " + why);
        s.status = "error: " + why;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.toastManager().addToast(new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("MCP Link script stopped"), Component.literal(s.name + ": " + why))));
    }

    // ---------------------------------------------------------------- handlers and timers (called from ScriptApi, under the lock)

    static int addHandler(Script s, String event, Pattern filter, Function fn) {
        if (!EVENTS.contains(event)) throw new IllegalArgumentException("Unknown event '" + event + "'. Events: " + new java.util.TreeSet<>(EVENTS));
        int id = IDS.incrementAndGet();
        s.handlers.add(new Script.Handler(id, s, event, fn, filter));
        rebuild();
        return id;
    }

    static int addTimer(Script s, int ticks, boolean repeat, Function fn) {
        int id = IDS.incrementAndGet();
        int every = Math.max(1, ticks);
        s.timers.add(new Script.Timer(id, repeat ? every : 0, fn, tick + every));
        return id;
    }

    static boolean remove(Script s, int id) {
        boolean removed = s.handlers.removeIf(h -> h.id() == id) | s.timers.removeIf(t -> t.id == id);
        if (removed) rebuild();
        return removed;
    }

    static void glow(Script s, Entity e, int rgb) {
        GLOWING.put(e.getId(), rgb & 0xFFFFFF);
        GLOW_OWNER.put(e.getId(), s);
    }

    static void unglow(Entity e) {
        GLOWING.remove(e.getId());
        GLOW_OWNER.remove(e.getId());
    }

    private static void rebuild() {
        Map<String, List<Script.Handler>> m = new HashMap<>();
        for (Script s : SCRIPTS.values()) {
            if (!s.running) continue;
            for (Script.Handler h : s.handlers) m.computeIfAbsent(h.event(), k -> new ArrayList<>()).add(h);
        }
        byEvent = m;
    }

    // ---------------------------------------------------------------- calling into scripts

    /**
     * Runs a script function with a time limit; returns its result as a Java value (null for undefined), or null after an
     * error, which is logged. Three timeouts or fifty errors in a row stop the script.
     */
    static Object call(Script s, Function fn, String what, long limitMs, Object... args) {
        if (!s.running) return null;
        s.lock.lock();
        long[] prev = Engine.limit(limitMs);
        Context cx = Engine.INSTANCE.enterContext();
        long t0 = System.nanoTime();
        try {
            if (!s.running) return null;
            Scriptable scope = s.scope;
            Object[] js = new Object[args.length];
            for (int i = 0; i < args.length; i++) js[i] = Context.javaToJS(args[i], scope, cx);
            Object r = fn.call(cx, scope, scope, js);
            s.errorsInRow = 0;
            return unwrap(r);
        } catch (Engine.Timeout t) {
            log(s, "error", what + " " + t.getMessage());
            if (++s.timeouts >= 3) kill(s, "too slow (" + what + " timed out 3 times)");
            return null;
        } catch (Throwable t) {
            log(s, "error", what + ": " + describe(t));
            if (++s.errorsInRow >= 50) kill(s, "50 errors in a row (last: " + describe(t) + ")");
            return null;
        } finally {
            long took = System.nanoTime() - t0;
            s.calls++;
            s.nanos += took;
            Context.exit();
            Engine.restore(prev);
            s.lock.unlock();
        }
    }

    static Object unwrap(Object r) {
        if (r == null || r instanceof Undefined) return null;
        if (r instanceof Wrapper w) return w.unwrap();
        if (r instanceof CharSequence cs) return cs.toString();
        return r;
    }

    static String describe(Throwable t) {
        if (t instanceof RhinoException re) {
            String trace = re.getScriptStackTrace();
            return re.details() + (re.lineNumber() > 0 ? " (line " + re.lineNumber() + ")" : "")
                    + (trace == null || trace.isBlank() ? "" : "\n" + trace.strip());
        }
        if (t instanceof Engine.Timeout) return t.getMessage();
        return t.toString();
    }

    private static void fire(String event, Object... args) {
        List<Script.Handler> hs = byEvent.get(event);
        if (hs == null) return;
        for (Script.Handler h : hs) call(h.script(), h.fn(), event, HANDLER_MS, args);
    }

    // ---------------------------------------------------------------- game events

    /** End of every client tick: starts saved scripts once loading is done, then timers and the tick event. */
    public static void tick(Minecraft mc) {
        if (!ready) {
            if (mc.gui == null || mc.gui.overlay() != null) return;
            ready = true;
            if (allowed()) startAll();
            return;
        }
        long now = ++tick;
        for (Script s : SCRIPTS.values()) {
            if (!s.running || s.timers.isEmpty()) continue;
            for (Script.Timer t : s.timers) {
                if (t.next > now) continue;
                if (t.interval == 0) s.timers.remove(t);
                else t.next = now + t.interval;
                call(s, t.fn, "timer", HANDLER_MS);
            }
        }
        Screen screen = mc.gui.screen();
        if (screen != lastScreen) {
            lastScreen = screen;
            fire("screen", screen);
        }
        fire("tick");
        synchronized (HUD) {
            HUD.values().removeIf(l -> l.until() != 0 && l.until() <= now);
        }
    }

    static long currentTick() {
        return tick;
    }

    public static void entityAdded(Entity e) {
        fire("entityAdded", e);
    }

    public static void entityRemoved(Entity e) {
        if (!GLOWING.isEmpty()) unglow(e);
        fire("entityRemoved", e);
    }

    /** Joining a world: entity ids now mean other entities. */
    public static void joined() {
        GLOWING.clear();
        GLOW_OWNER.clear();
    }

    /** Incoming chat or system message; false hides it. */
    public static boolean chatIn(Component msg, String kind) {
        List<Script.Handler> hs = byEvent.get("chat");
        if (hs == null) return true;
        String text = msg.getString();
        for (Script.Handler h : hs) {
            if (h.filter() != null && !h.filter().matcher(text).find()) continue;
            if (call(h.script(), h.fn(), "chat", HANDLER_MS, text, msg, kind) == Boolean.FALSE) return false;
        }
        return true;
    }

    /** {original, replacement} between Fabric's allow and modify events, which run back to back on the same thread. */
    private static final ThreadLocal<String[]> REWRITE = new ThreadLocal<>();

    /** Chat or command the player is sending (commands without the slash); false cancels it. */
    public static boolean chatOut(String text, boolean command) {
        REWRITE.remove();
        List<Script.Handler> hs = byEvent.get("chatSend");
        if (hs == null) return true;
        String cur = text;
        for (Script.Handler h : hs) {
            if (h.filter() != null && !h.filter().matcher(cur).find()) continue;
            Object r = call(h.script(), h.fn(), "chatSend", HANDLER_MS, cur, command);
            if (r == Boolean.FALSE) return false;
            if (r instanceof String s && !s.isEmpty()) cur = s;
        }
        if (!cur.equals(text)) REWRITE.set(new String[]{text, cur});
        return true;
    }

    public static String rewriteChatOut(String text) {
        String[] rw = REWRITE.get();
        if (rw == null) return text;
        REWRITE.remove();
        return rw[0].equals(text) ? rw[1] : text;
    }

    private static final ClassValue<String> TYPE = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return PacketLog.typeName(type);
        }
    };

    public static boolean wantsPacketsIn() {
        return byEvent.containsKey("packetIn");
    }

    public static boolean wantsPacketsOut() {
        return byEvent.containsKey("packetOut");
    }

    /** A packet arriving from the server (network thread): null keeps it, CANCEL drops it, a packet replaces it. */
    public static Object packetIn(Object packet) {
        return packet("packetIn", packet);
    }

    /** A packet the client is about to send (sending thread, usually the game's): same answers as packetIn. */
    public static Object packetOut(Object packet) {
        return packet("packetOut", packet);
    }

    private static Object packet(String event, Object packet) {
        List<Script.Handler> hs = byEvent.get(event);
        if (hs == null) return null;
        Object cur = packet;
        String type = TYPE.get(cur.getClass());
        for (Script.Handler h : hs) {
            if (h.filter() != null && !h.filter().matcher(type).find()) continue;
            Object r = call(h.script(), h.fn(), event, HANDLER_MS, cur, type);
            if (r == Boolean.FALSE) return CANCEL;
            if (r instanceof net.minecraft.network.protocol.Packet<?> p && p != cur) {
                cur = p;
                type = TYPE.get(cur.getClass());
            }
        }
        return cur == packet ? null : cur;
    }

    /** HUD lines and "hud" handlers, drawn from the top-left tag downwards. */
    public static void renderHud(GuiGraphicsExtractor g, int y) {
        Minecraft mc = Minecraft.getInstance();
        if (!HUD.isEmpty()) {
            List<HudLine> lines;
            synchronized (HUD) {
                lines = new ArrayList<>(HUD.values());
            }
            for (HudLine l : lines) {
                int w = mc.font.width(l.text());
                g.fill(2, y, 6 + w, y + 12, 0x90000000);
                g.text(mc.font, l.text(), 4, y + 2, 0xFF000000 | l.color(), false);
                y += 12;
            }
        }
        List<Script.Handler> hs = byEvent.get("hud");
        if (hs == null) return;
        ScriptApi.Draw draw = new ScriptApi.Draw(g, y);
        for (Script.Handler h : hs) call(h.script(), h.fn(), "hud", HANDLER_MS, draw);
    }

    // ---------------------------------------------------------------- eval

    record EvalResult(Object value, String json, List<String> logs) {
    }

    /** Runs code once on the client thread in a fresh scope (no handlers or timers), and returns its last value. */
    static EvalResult eval(String code) {
        Script s = new Script("(eval)", true);
        List<String> out = new ArrayList<>();
        s.lock.lock();
        long[] prev = Engine.limit(EVAL_MS);
        Context cx = Engine.INSTANCE.enterContext();
        try {
            ImporterTopLevel scope = new ImporterTopLevel(cx);
            ScriptApi api = new ScriptApi(s);
            api.evalLog = out;
            ScriptableObject.putProperty(scope, "game", Context.javaToJS(api, scope, cx));
            ScriptableObject.putProperty(scope, "mc", Context.javaToJS(Minecraft.getInstance(), scope, cx));
            s.scope = scope;
            s.running = true;
            Object r = cx.evaluateString(scope, code, "eval.js", 1, null);
            if (r instanceof Scriptable sc && !(r instanceof Wrapper) && !(r instanceof Function)) {
                Object json = NativeJSON.stringify(cx, scope, sc, null, null);
                return new EvalResult(null, json instanceof CharSequence c ? c.toString() : String.valueOf(json), out);
            }
            return new EvalResult(unwrap(r), null, out);
        } catch (Throwable t) {
            throw new RuntimeException(describe(t));
        } finally {
            s.running = false;
            Context.exit();
            Engine.restore(prev);
            s.lock.unlock();
        }
    }

    // ---------------------------------------------------------------- logs

    static void log(Script s, String level, String text) {
        synchronized (LOGS) {
            LOGS.addLast(new LogLine(++logSeq, System.currentTimeMillis(), s.name, level, text));
            while (LOGS.size() > 2000) LOGS.removeFirst();
        }
        if (level.equals("error")) McpLink.LOG.warn("Script {}: {}", s.name, text);
    }

    static List<LogLine> logs(String script, long after, int limit) {
        List<LogLine> out = new ArrayList<>();
        synchronized (LOGS) {
            for (LogLine l : LOGS) {
                if (l.seq() <= after) continue;
                if (script != null && !l.script().equals(script)) continue;
                out.add(l);
            }
        }
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    static long lastLogSeq() {
        synchronized (LOGS) {
            return logSeq;
        }
    }

    public static int glowColor(Entity e) {
        Integer c = GLOWING.get(e.getId());
        return c == null ? -1 : c;
    }
}
