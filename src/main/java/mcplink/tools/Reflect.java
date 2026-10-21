package mcplink.tools;

import mcplink.McpLink;
import mcplink.Game;
import mcplink.mcp.ToolRegistry.ToolError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reflection over the live client: resolve a path like {@code level.players[0].inventory} from a root object and render any
 * object as JSON (fields, collections, maps, records). Reads fields and side-effect-free getters only.
 */
public final class Reflect {
    private Reflect() {
    }

    /** Follows a path (fields, getters, [n], {key}) from any object, private fields included. For scripts. */
    public static Object walk(Object from, String path) {
        Object o = from;
        StringBuilder walked = new StringBuilder();
        for (String s : segments(path.trim())) {
            if (o == null) throw new ToolError((walked.isEmpty() ? "the start" : walked) + " is null");
            o = step(o, s);
            walked.append(s.startsWith("[") || s.startsWith("{") ? "" : ".").append(s);
        }
        return o;
    }

    /**
     * Calls a method by name (private ones too, statics when the target is a Class), picking the first overload whose
     * parameters the arguments fit. Numbers from JavaScript arrive as doubles and are narrowed to the parameter type.
     */
    public static Object invoke(Object target, String name, Object[] args) throws Exception {
        boolean statics = target instanceof Class<?>;
        Class<?> cls = statics ? (Class<?>) target : target.getClass();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                if (Modifier.isStatic(m.getModifiers()) != statics && statics) continue;
                Object[] conv = coerce(m.getParameterTypes(), args);
                if (conv == null) continue;
                m.setAccessible(true);
                try {
                    return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : target, conv);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause() instanceof Exception ex ? ex : new RuntimeException(e.getCause());
                }
            }
        }
        throw new ToolError("No method " + name + " with " + args.length + " fitting argument(s) on " + cls.getName());
    }

    private static Object[] coerce(Class<?>[] types, Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i] instanceof org.mozilla.javascript.Wrapper w ? w.unwrap() : args[i];
            if (a instanceof CharSequence cs && !(a instanceof String)) a = cs.toString();
            Class<?> t = types[i];
            if (a == null) {
                if (t.isPrimitive()) return null;
                out[i] = null;
            } else if (a instanceof Number n && (t == int.class || t == Integer.class)) out[i] = n.intValue();
            else if (a instanceof Number n && (t == long.class || t == Long.class)) out[i] = n.longValue();
            else if (a instanceof Number n && (t == float.class || t == Float.class)) out[i] = n.floatValue();
            else if (a instanceof Number n && (t == double.class || t == Double.class)) out[i] = n.doubleValue();
            else if (a instanceof Number n && (t == short.class || t == Short.class)) out[i] = n.shortValue();
            else if (a instanceof Number n && (t == byte.class || t == Byte.class)) out[i] = n.byteValue();
            else if (a instanceof Boolean b && (t == boolean.class || t == Boolean.class)) out[i] = b;
            else if (t.isInstance(a)) out[i] = a;
            else return null;
        }
        return out;
    }

    static final String ROOTS = "mc (Minecraft), level, player, connection (packet listener), gameMode, gui, hud, options, screen, "
            + "hitResult, camera, registries (registry access), server (integrated server, singleplayer only), and static:<class name> "
            + "for a class's static fields (e.g. static:net.minecraft.client.KeyMapping)";

    static Object root(String name) {
        Minecraft mc = Minecraft.getInstance();
        return switch (name) {
            case "mc", "minecraft" -> mc;
            case "level" -> mc.level;
            case "player" -> mc.player;
            case "connection" -> mc.getConnection();
            case "gameMode" -> mc.gameMode;
            case "gui" -> mc.gui;
            case "hud" -> mc.gui.hud;
            case "options" -> mc.options;
            case "screen" -> mc.gui.screen();
            case "hitResult" -> mc.hitResult;
            case "camera" -> mc.gameRenderer.mainCamera();
            case "registries" -> mc.getConnection() == null ? null : mc.getConnection().registryAccess();
            case "server" -> mc.getSingleplayerServer();
            default -> {
                if (name.startsWith("static:")) {
                    try {
                        yield Class.forName(name.substring(7), false, McpLink.class.getClassLoader());
                    } catch (ClassNotFoundException e) {
                        throw new ToolError("No class " + name.substring(7));
                    }
                }
                throw new ToolError("Unknown root '" + name + "'. Roots: " + ROOTS);
            }
        };
    }

    // ---------------------------------------------------------------- paths

    /** Splits "a.b[2].c{key}.d()" into segments: a, b, [2], c, {key}, d(). */
    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' ) {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '[' || c == '{') {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
                char close = c == '[' ? ']' : '}';
                int end = path.indexOf(close, i);
                if (end < 0) throw new ToolError("Unclosed " + c + " in path");
                out.add(path.substring(i, end + 1));
                i = end;
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    static Object resolve(String path) {
        List<String> segs = segments(path.trim());
        if (segs.isEmpty()) throw new ToolError("Empty path");
        String first = segs.get(0);
        // static:a.b.C keeps its dots
        int start = 1;
        if (first.startsWith("static:")) {
            StringBuilder cls = new StringBuilder(first);
            Class<?> found = null;
            int i = 1;
            for (; ; i++) {
                try {
                    found = Class.forName(cls.substring(7), false, McpLink.class.getClassLoader());
                    break;
                } catch (ClassNotFoundException e) {
                    if (i >= segs.size() || segs.get(i).startsWith("[") || segs.get(i).startsWith("{")) throw new ToolError("No class " + cls.substring(7));
                    cls.append('.').append(segs.get(i));
                }
            }
            first = "static:" + found.getName();
            start = i + 1;
        }
        Object o = root(first);
        StringBuilder walked = new StringBuilder(first);
        for (int i = start; i < segs.size(); i++) {
            String s = segs.get(i);
            if (o == null) throw new ToolError(walked + " is null");
            o = step(o, s);
            walked.append(s.startsWith("[") || s.startsWith("{") ? "" : ".").append(s);
        }
        return o;
    }

    private static Object step(Object o, String seg) {
        if (seg.startsWith("[")) {
            int idx;
            try {
                idx = Integer.parseInt(seg.substring(1, seg.length() - 1).trim());
            } catch (NumberFormatException e) {
                throw new ToolError("Index must be a number: " + seg);
            }
            return nth(o, idx);
        }
        if (seg.startsWith("{")) return keyed(o, seg.substring(1, seg.length() - 1).trim());
        boolean call = seg.endsWith("()");
        String name = call ? seg.substring(0, seg.length() - 2) : seg;
        Class<?> cls = o instanceof Class<?> c ? c : o.getClass();
        Object target = o instanceof Class<?> ? null : o;
        if (!call) {
            Field f = field(cls, name, target == null);
            if (f != null) return get(f, target);
        }
        Method m = getter(cls, name, target == null);
        if (m == null && !call) m = getter(cls, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1), target == null);
        if (m == null && !call) m = getter(cls, "is" + Character.toUpperCase(name.charAt(0)) + name.substring(1), target == null);
        if (m == null) throw new ToolError("No field or readable getter '" + name + "' on " + cls.getName());
        try {
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception e) {
            throw new ToolError("Calling " + name + "() failed: " + e);
        }
    }

    static Object nth(Object o, int idx) {
        if (o.getClass().isArray()) return Array.get(o, idx);
        if (o instanceof List<?> l) return l.get(idx);
        if (o instanceof Map<?, ?> m) o = m.entrySet();
        if (o instanceof Iterable<?> it) {
            int i = 0;
            for (Object x : it) {
                if (i++ == idx) return x;
            }
            throw new ToolError("Index " + idx + " out of range");
        }
        throw new ToolError("Can't index into " + o.getClass().getSimpleName());
    }

    static Object keyed(Object o, String key) {
        if (o instanceof net.minecraft.core.RegistryAccess ra) {
            Identifier id = Identifier.tryParse(key.contains(":") ? key : "minecraft:" + key);
            if (id == null) throw new ToolError("Bad registry id " + key);
            return ra.lookup(net.minecraft.resources.ResourceKey.createRegistryKey(id)).orElseThrow(() -> new ToolError("No registry " + key));
        }
        if (o instanceof Registry<?> r) {
            Identifier id = Identifier.tryParse(key.contains(":") ? key : "minecraft:" + key);
            Object v = id == null ? null : r.getValue(id);
            if (v == null) throw new ToolError("No entry " + key + " in " + r.key());
            return v;
        }
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (String.valueOf(e.getKey()).equals(key) || label(e.getKey()).equals(key)) return e.getValue();
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (label(e.getKey()).contains(key)) return e.getValue();
            }
            throw new ToolError("No key matching " + key);
        }
        throw new ToolError("{key} only works on maps and registries, not " + o.getClass().getSimpleName());
    }

    static Field field(Class<?> cls, String name, boolean statics) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers()) == statics) return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** Words that mark a method as doing something rather than reading (matched against the camelCase words of its name). */
    private static final java.util.Set<String> BAD = java.util.Set.of("set", "remove", "clear", "reset", "close", "disconnect", "tick", "send",
            "start", "stop", "destroy", "delete", "save", "write", "drop", "consume", "toggle", "release", "create", "open", "load", "reload",
            "execute", "run", "schedule", "poll", "take", "pop", "flush", "apply", "update", "refresh", "init", "register", "increment",
            "decrement", "compute", "merge", "swap", "move", "select", "place", "attack", "use", "interact", "spawn", "kill", "discard",
            "teleport", "push", "play", "stream", "iterator", "add", "put", "and", "mark", "invalidate", "trigger", "fire", "emit");

    private static boolean doesSomething(String name) {
        for (String w : name.split("(?<=[a-z0-9])(?=[A-Z])|_")) {
            if (BAD.contains(w.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /** Zero-argument methods that only read: get/is/has/can/... accessors and record components. */
    static boolean readable(Method m) {
        if (m.getParameterCount() != 0 || m.getReturnType() == void.class) return false;
        String n = m.getName();
        if (m.getDeclaringClass().isRecord()) {
            for (RecordComponent rc : m.getDeclaringClass().getRecordComponents()) {
                if (rc.getName().equals(n)) return true;
            }
        }
        boolean shape = n.startsWith("get") || n.startsWith("is") || n.startsWith("has") || n.startsWith("can") || n.startsWith("should")
                || java.util.Set.of("size", "length", "name", "ordinal", "value", "key", "keySet", "values", "entrySet", "unwrapKey", "toString",
                "registryAccess", "position", "blockPosition", "dimension", "level", "components", "listElements", "listTags").contains(n);
        return shape && !doesSomething(n.startsWith("get") ? n.substring(3) : n);
    }

    static Method getter(Class<?> cls, String name, boolean statics) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0 && Modifier.isStatic(m.getModifiers()) == statics && readable(m)) return m;
            }
        }
        for (Class<?> i : allInterfaces(cls)) {
            for (Method m : i.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0 && Modifier.isStatic(m.getModifiers()) == statics && readable(m)) return m;
            }
        }
        return null;
    }

    private static List<Class<?>> allInterfaces(Class<?> cls) {
        List<Class<?>> out = new ArrayList<>();
        ArrayDeque<Class<?>> todo = new ArrayDeque<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) todo.addAll(List.of(c.getInterfaces()));
        while (!todo.isEmpty()) {
            Class<?> i = todo.poll();
            if (out.contains(i)) continue;
            out.add(i);
            todo.addAll(List.of(i.getInterfaces()));
        }
        return out;
    }

    static Object get(Field f, Object target) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new ToolError("Can't read " + f.getName() + ": " + e);
        }
    }

    // ---------------------------------------------------------------- rendering

    static final class Opts {
        int limit = 50;
        int offset = 0;
        int maxString = 2000;
        Pattern fieldFilter;
        boolean getters;
        boolean statics;
        /** Work budget for one render, so a deep or wide object graph can't run away: values visited and characters produced. */
        int maxNodes = 20_000;
        long maxChars = 120_000;
        int nodes;
        long chars;
        /** Set when the budget ran out and parts of the output were cut. */
        boolean truncated;

        void resetBudget() {
            nodes = maxNodes;
            chars = maxChars;
            truncated = false;
        }
    }

    /** Renders any object as JSON with the default limits (for script results). */
    public static JsonElement show(Object o, int depth) {
        return render(o, depth, new Opts());
    }

    static JsonElement render(Object o, int depth, Opts opts) {
        opts.resetBudget();
        return render(o, depth, opts, new IdentityHashMap<>(), true);
    }

    /** True while there is budget left; marks the output as cut when there isn't. */
    private static boolean budget(Opts opts) {
        if (opts.nodes > 0 && opts.chars > 0) return true;
        opts.truncated = true;
        return false;
    }

    private static JsonPrimitive cost(JsonPrimitive p, Opts opts) {
        opts.chars -= p.isString() ? p.getAsString().length() + 4 : 8;
        return p;
    }

    /** A short readable label for values that have one (ids, keys, text, positions). */
    static String label(Object o) {
        if (o == null) return "null";
        if (o instanceof Holder<?> h) return h.unwrapKey().map(k -> k.identifier().toString()).orElseGet(() -> label(h.value()));
        if (o instanceof Component c) return c.getString();
        String id = registryId(o);
        return id != null ? id : String.valueOf(o);
    }

    /** Every value of the game's built-in registries (entity types, blocks, items, sounds...) by identity, with its id. */
    private static volatile IdentityHashMap<Object, String> registryIds;

    /** The registry id of a built-in registry value (minecraft:enderman for the enderman EntityType), or null. */
    static String registryId(Object o) {
        IdentityHashMap<Object, String> m = registryIds;
        if (m == null) {
            m = new IdentityHashMap<>();
            for (Registry<?> r : BuiltInRegistries.REGISTRY) {
                for (var e : r.entrySet()) m.putIfAbsent(e.getValue(), e.getKey().identifier().toString());
            }
            registryIds = m;
        }
        return m.get(o);
    }

    private static boolean simple(Object o) {
        return o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof CharSequence || o instanceof Enum<?>
                || o instanceof Identifier || o instanceof net.minecraft.resources.ResourceKey<?> || o instanceof java.util.UUID
                || o instanceof net.minecraft.core.Vec3i || o instanceof net.minecraft.world.phys.Vec3 || o instanceof net.minecraft.world.level.ChunkPos
                || o instanceof Component || o instanceof Class<?> || o instanceof net.minecraft.world.level.block.state.BlockState
                || o instanceof java.time.temporal.Temporal;
    }

    private static JsonPrimitive renderSimple(Object o, Opts opts) {
        if (o instanceof Boolean b) return new JsonPrimitive(b);
        if (o instanceof Double d && (d.isNaN() || d.isInfinite())) return new JsonPrimitive(d.toString());
        if (o instanceof Float f && (f.isNaN() || f.isInfinite())) return new JsonPrimitive(f.toString());
        if (o instanceof Number n) return new JsonPrimitive(n);
        if (o instanceof Character c) return new JsonPrimitive(c);
        if (o instanceof Enum<?> e) return new JsonPrimitive(e.name());
        if (o instanceof Class<?> c) return new JsonPrimitive("class " + c.getName());
        if (o instanceof net.minecraft.world.level.block.state.BlockState s) return new JsonPrimitive(Game.state(s));
        String s = label(o);
        if (s.length() > opts.maxString) s = s.substring(0, opts.maxString) + "...(" + s.length() + " chars)";
        return new JsonPrimitive(s);
    }

    private static JsonElement render(Object o, int depth, Opts opts, IdentityHashMap<Object, Boolean> seen, boolean top) {
        if (o == null) return JsonNull.INSTANCE;
        if (!budget(opts)) return new JsonPrimitive("(output limit reached)");
        opts.nodes--;
        if (simple(o)) return cost(renderSimple(o, opts), opts);
        if (o instanceof Optional<?> op) return op.isPresent() ? render(op.get(), depth, opts, seen, top) : JsonNull.INSTANCE;
        if (o instanceof Holder<?> h && h.unwrapKey().isPresent() && !top) return cost(new JsonPrimitive(label(h)), opts);
        if (!top && !(o instanceof Registry<?>)) {
            // a nested entity type, block, item... reads better as its id than as all its fields
            String id = registryId(o);
            if (id != null) return cost(new JsonPrimitive(id), opts);
        }
        if (seen.containsKey(o)) return cost(new JsonPrimitive("(already shown: " + o.getClass().getSimpleName() + ")"), opts);
        if (o instanceof Registry<?> r) {
            JsonObject j = new JsonObject();
            j.addProperty("_registry", r.key().identifier().toString());
            j.addProperty("size", r.size());
            if (depth > 0) {
                JsonArray ids = new JsonArray();
                r.keySet().stream().map(Identifier::toString).sorted().skip(top ? opts.offset : 0).limit(opts.limit).forEach(ids::add);
                j.add("ids", ids);
            }
            return j;
        }
        if (depth < 0) return cost(new JsonPrimitive(o.getClass().getSimpleName() + shortToString(o)), opts);
        seen.put(o, true);
        try {
            if (o.getClass().isArray()) {
                int n = Array.getLength(o);
                JsonObject j = new JsonObject();
                j.addProperty("_array", o.getClass().getComponentType().getSimpleName() + "[" + n + "]");
                JsonArray items = new JsonArray();
                int from = top ? Math.min(opts.offset, n) : 0;
                for (int i = from; i < Math.min(n, from + opts.limit) && budget(opts); i++) items.add(render(Array.get(o, i), depth - 1, opts, seen, false));
                j.add("items", items);
                return j;
            }
            if (o instanceof Map<?, ?> m) {
                JsonObject j = new JsonObject();
                j.addProperty("_map", o.getClass().getSimpleName());
                j.addProperty("size", m.size());
                if (depth == 0) return j;
                JsonObject entries = new JsonObject();
                int i = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (top && i++ < opts.offset) continue;
                    if (entries.size() >= opts.limit || !budget(opts)) break;
                    String key = label(e.getKey());
                    opts.chars -= key.length() + 4;
                    entries.add(key, render(e.getValue(), depth - 1, opts, seen, false));
                }
                j.add("entries", entries);
                return j;
            }
            if (o instanceof Collection<?> || o instanceof Iterable<?>) {
                JsonObject j = new JsonObject();
                j.addProperty("_collection", o.getClass().getSimpleName());
                if (o instanceof Collection<?> c) j.addProperty("size", c.size());
                if (depth == 0) return j;
                JsonArray items = new JsonArray();
                Iterator<?> it = ((Iterable<?>) o).iterator();
                int i = 0;
                while (it.hasNext() && items.size() < opts.limit && budget(opts)) {
                    Object x = it.next();
                    if (top && i++ < opts.offset) continue;
                    items.add(render(x, depth - 1, opts, seen, false));
                }
                j.add("items", items);
                if (it.hasNext()) j.addProperty("more", true);
                return j;
            }
            if (o.getClass().getName().startsWith("java.") || o.getClass().getName().startsWith("jdk.") || o.getClass().getName().startsWith("sun.")) {
                return cost(new JsonPrimitive(o.getClass().getSimpleName() + shortToString(o)), opts);
            }
            return renderObject(o, depth, opts, seen, top);
        } finally {
            seen.remove(o);
        }
    }

    private static String shortToString(Object o) {
        try {
            Method ts = o.getClass().getMethod("toString");
            if (ts.getDeclaringClass() == Object.class) return "";
            String s = String.valueOf(o);
            return s.length() > 160 ? " " + s.substring(0, 160) + "..." : " " + s;
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonObject renderObject(Object o, int depth, Opts opts, IdentityHashMap<Object, Boolean> seen, boolean top) {
        boolean isClass = o instanceof Class<?>;
        Class<?> cls = isClass ? (Class<?>) o : o.getClass();
        JsonObject j = new JsonObject();
        j.addProperty("_class", cls.getName());
        String ts = isClass ? "" : shortToString(o).trim();
        if (!ts.isEmpty()) {
            j.addProperty("_toString", ts);
            opts.chars -= ts.length() + 12;
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                boolean st = Modifier.isStatic(f.getModifiers());
                if (f.isSynthetic() || (isClass != st && !(top && opts.statics && st))) continue;
                if (top && opts.fieldFilter != null && !opts.fieldFilter.matcher(f.getName()).find()) continue;
                fields.putIfAbsent(fields.containsKey(f.getName()) ? c.getSimpleName() + "." + f.getName() : f.getName(), f);
            }
            if (isClass) break;
        }
        for (var e : fields.entrySet()) {
            if (!budget(opts)) {
                j.addProperty("_cut", "output limit reached");
                break;
            }
            Field f = e.getValue();
            JsonElement v;
            try {
                f.setAccessible(true);
                Object val = f.get(isClass ? null : o);
                v = render(val, depth - 1, opts, seen, false);
            } catch (Throwable t) {
                v = new JsonPrimitive("(unreadable: " + t.getClass().getSimpleName() + ")");
            }
            opts.chars -= e.getKey().length() + 4;
            j.add(e.getKey(), v);
        }
        if (top && opts.getters && !isClass) {
            JsonObject gs = new JsonObject();
            for (Method m : cls.getMethods()) {
                if (!budget(opts)) break;
                if (Modifier.isStatic(m.getModifiers()) || !readable(m) || m.getName().equals("toString") || m.getName().equals("getClass")) continue;
                if (opts.fieldFilter != null && !opts.fieldFilter.matcher(m.getName()).find()) continue;
                try {
                    gs.add(m.getName() + "()", render(m.invoke(o), depth - 1, opts, seen, false));
                } catch (Throwable t) {
                    gs.addProperty(m.getName() + "()", "(error: " + t.getClass().getSimpleName() + ")");
                }
            }
            j.add("_getters", gs);
        }
        return j;
    }

    // ---------------------------------------------------------------- search

    record Hit(String path, String type, String value) {
    }

    /** Breadth-first walk of the object graph below a root, collecting paths whose field name, class or value matches. */
    static List<Hit> search(Object root, String rootPath, Pattern pattern, int maxDepth, boolean values, int maxHits, int maxVisits) {
        List<Hit> hits = new ArrayList<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        record Node(Object o, String path, int depth) {
        }
        ArrayDeque<Node> todo = new ArrayDeque<>();
        todo.add(new Node(root, rootPath, 0));
        int visits = 0;
        while (!todo.isEmpty() && hits.size() < maxHits && visits < maxVisits) {
            Node n = todo.poll();
            Object o = n.o();
            if (o == null || simple(o) || seen.containsKey(o)) continue;
            seen.put(o, true);
            visits++;
            String cn = o.getClass().getName();
            if (cn.startsWith("java.lang.") || cn.startsWith("jdk.") || cn.startsWith("sun.") || cn.startsWith("com.mojang.blaze3d")
                    || cn.startsWith("org.lwjgl") || cn.startsWith("io.netty")) continue;
            if (o instanceof Map<?, ?> m) {
                int i = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (i++ >= 20) break;
                    String p = n.path() + "{" + label(e.getKey()) + "}";
                    check(hits, p, e.getValue(), label(e.getKey()), pattern, values);
                    if (n.depth() < maxDepth) todo.add(new Node(e.getValue(), p, n.depth() + 1));
                }
                continue;
            }
            if (o instanceof Iterable<?> it && !(o instanceof Registry<?>)) {
                int i = 0;
                for (Object x : it) {
                    if (i >= 20) break;
                    String p = n.path() + "[" + i++ + "]";
                    check(hits, p, x, "", pattern, values);
                    if (n.depth() < maxDepth) todo.add(new Node(x, p, n.depth() + 1));
                }
                continue;
            }
            if (o.getClass().isArray()) {
                int len = Math.min(20, Array.getLength(o));
                for (int i = 0; i < len; i++) {
                    Object x = Array.get(o, i);
                    String p = n.path() + "[" + i + "]";
                    check(hits, p, x, "", pattern, values);
                    if (n.depth() < maxDepth) todo.add(new Node(x, p, n.depth() + 1));
                }
                continue;
            }
            if (cn.startsWith("java.")) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable t) {
                        continue;
                    }
                    String p = n.path() + "." + f.getName();
                    check(hits, p, v, f.getName(), pattern, values);
                    if (n.depth() < maxDepth) todo.add(new Node(v, p, n.depth() + 1));
                }
            }
        }
        return hits;
    }

    private static void check(List<Hit> hits, String path, Object v, String name, Pattern pattern, boolean values) {
        String type = v == null ? "null" : v.getClass().getSimpleName();
        boolean match = pattern.matcher(name).find() || (v != null && pattern.matcher(v.getClass().getName()).find());
        String shown = null;
        if (v != null && simple(v)) {
            shown = label(v);
            if (shown.length() > 200) shown = shown.substring(0, 200) + "...";
            if (values && pattern.matcher(shown).find()) match = true;
        }
        if (match) hits.add(new Hit(path, type, shown));
    }
}
