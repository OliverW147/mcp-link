package mcplink.script;

import mcplink.Game;
import mcplink.control.Control;
import mcplink.mixin.MinecraftInvoker;
import mcplink.tools.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.StringUtils;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJavaClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The `game` object every script gets. Handlers and timers run on the game thread, except packetIn (network thread) and
 * packetOut (whichever thread sends). Methods that change the game hop to the game thread on their own when called from another
 * one; use game.run(fn) to do more than that from a packet handler.
 */
public final class ScriptApi {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);

    private final Script script;
    /** Collects game.log output during script_eval. */
    List<String> evalLog;

    ScriptApi(Script script) {
        this.script = script;
    }

    private static Minecraft mc0() {
        return Minecraft.getInstance();
    }

    /** Runs now on the game thread, or soon when called from another thread (never waits, so it can't deadlock). */
    private static void onGame(Runnable r) {
        Minecraft mc = mc0();
        if (mc.isSameThread()) r.run();
        else mc.execute(r);
    }

    private void noEval(String what) {
        if (script.eval) throw new IllegalStateException(what + " needs a saved script (script_install); script_eval runs once.");
    }

    // ---------------------------------------------------------------- the game

    public Minecraft mc() {
        return mc0();
    }

    public LocalPlayer player() {
        return mc0().player;
    }

    public Object level() {
        return mc0().level;
    }

    /** Ticks since scripts started (20 per second). */
    public long tick() {
        return Scripts.currentTick();
    }

    public String name() {
        return script.name;
    }

    public boolean controlOn() {
        return Control.enabled();
    }

    // ---------------------------------------------------------------- events and timers

    /** Events: tick, entityAdded, entityRemoved, chat, chatSend, packetIn, packetOut, hud, screen, stop. Returns an id for off(). */
    public int on(String event, Function fn) {
        noEval("on()");
        return Scripts.addHandler(script, event, null, fn);
    }

    /** Same, with a regex the packet type (packetIn/packetOut) or the message text (chat/chatSend) must contain. */
    public int on(String event, String filter, Function fn) {
        noEval("on()");
        return Scripts.addHandler(script, event, filter == null ? null : Pattern.compile(filter), fn);
    }

    public boolean off(int id) {
        return Scripts.remove(script, id);
    }

    public int every(int ticks, Function fn) {
        noEval("every()");
        return Scripts.addTimer(script, ticks, true, fn);
    }

    public int after(int ticks, Function fn) {
        noEval("after()");
        return Scripts.addTimer(script, ticks, false, fn);
    }

    /** Runs fn on the game thread (from a packet handler, for example). */
    public void run(Function fn) {
        onGame(() -> Scripts.call(script, fn, "run", Scripts.HANDLER_MS));
    }

    /** Stops this script (it stays installed). */
    public void stop() {
        onGame(() -> Scripts.stop(script, "stopped itself"));
    }

    public void log(Object msg) {
        Object v = Scripts.unwrap(msg);
        // JavaScript numbers are doubles: show 7 rather than 7.0
        String text = v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : String.valueOf(v);
        if (evalLog != null) evalLog.add(text);
        else Scripts.log(script, "info", text);
    }

    // ---------------------------------------------------------------- getting the player's attention (only they see these)

    /** A toast in the corner with a ping. */
    public void notify(String title, String text) {
        onGame(() -> {
            Minecraft mc = mc0();
            mc.gui.toastManager().addToast(new SystemToast(TOAST, Component.literal(title), text == null ? null : Component.literal(text)));
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5F));
        });
    }

    /** A chat line only the player sees (never sent to the server). */
    public void chat(String text) {
        onGame(() -> mc0().gui.hud.getChat().addClientSystemMessage(Component.literal(text)));
    }

    public void actionbar(String text) {
        onGame(() -> {
            if (mc0().player != null) mc0().player.sendOverlayMessage(Component.literal(text));
        });
    }

    public void ping() {
        onGame(() -> mc0().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5F)));
    }

    /** Plays a sound by id (e.g. minecraft:entity.goat.screaming.ambient) for the player only. */
    public void sound(String id, double volume, double pitch) {
        SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(Game.normId(id)));
        if (ev == null) throw new IllegalArgumentException("Unknown sound " + id);
        onGame(() -> mc0().getSoundManager().play(SimpleSoundInstance.forUI(ev, (float) pitch, (float) volume)));
    }

    /** A line of text on the HUD (under the MCP tag) until cleared. Colour is 0xRRGGBB. */
    public void hud(String key, String text) {
        hud(key, text, 0xFFFFFF);
    }

    public void hud(String key, String text, int color) {
        Scripts.HUD.put(script.name + ":" + key, new Scripts.HudLine(script, text, color & 0xFFFFFF, 0));
    }

    /** A HUD line that disappears after the given number of ticks. */
    public void hudFor(String key, String text, int color, int ticks) {
        Scripts.HUD.put(script.name + ":" + key, new Scripts.HudLine(script, text, color & 0xFFFFFF, Scripts.currentTick() + Math.max(1, ticks)));
    }

    public void hudClear(String key) {
        Scripts.HUD.remove(script.name + ":" + key);
    }

    /** Outlines an entity (seen through walls) in a colour, 0xRRGGBB. Only on this client. */
    public void glow(Object entity, int color) {
        Scripts.glow(script, entity(entity), color);
    }

    public void glow(Object entity) {
        glow(entity, 0xFFFF55);
    }

    public void unglow(Object entity) {
        Scripts.unglow(entity(entity));
    }

    // ---------------------------------------------------------------- entities

    /** An entity from an Entity or an entity id. */
    public Entity entity(Object e) {
        Object v = Scripts.unwrap(e);
        if (v instanceof Entity en) return en;
        if (v instanceof Number n && mc0().level != null) {
            Entity en = mc0().level.getEntity(n.intValue());
            if (en != null) return en;
        }
        throw new IllegalArgumentException("Not an entity or entity id: " + v);
    }

    /** Type id of an entity, e.g. minecraft:goat. */
    public String id(Object e) {
        return Game.id(entity(e));
    }

    public double dist(Object e) {
        LocalPlayer p = mc0().player;
        return p == null ? Double.NaN : p.distanceTo(entity(e));
    }

    /** All entities the client knows, nearest first. */
    public List<Entity> entities() {
        return entities(null, Double.MAX_VALUE);
    }

    /** Entities whose type id matches a glob (villager, *zombie*, minecraft:player), within a radius of the player, nearest first. */
    public List<Entity> entities(String type, double radius) {
        Minecraft mc = mc0();
        List<Entity> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) return out;
        Pattern p = type == null ? null : Pattern.compile(Pattern.quote(Game.normId(type)).replace("*", "\\E.*\\Q"));
        double r2 = radius * radius;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (p != null && !p.matcher(Game.id(e)).matches()) continue;
            if (e.distanceToSqr(mc.player) > r2) continue;
            out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
        return out;
    }

    public Entity nearest(String type, double radius) {
        List<Entity> l = entities(type, radius);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Other players in view. */
    public List<Player> players() {
        List<Player> out = new ArrayList<>();
        Minecraft mc = mc0();
        if (mc.level == null) return out;
        for (Player p : mc.level.players()) if (p != mc.player) out.add(p);
        return out;
    }

    // ---------------------------------------------------------------- acting (the game's own click handling, like the mouse)

    public void lookAt(double x, double y, double z) {
        onGame(() -> {
            if (mc0().player != null) look(mc0().player, new Vec3(x, y, z));
        });
    }

    public void lookAt(Object entity) {
        Entity e = entity(entity);
        onGame(() -> {
            if (mc0().player != null) look(mc0().player, e.getBoundingBox().getCenter());
        });
    }

    private static void look(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = Mth.clamp((float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI)), -90.0F, 90.0F);
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    /** Turns to an entity and left-clicks it like the mouse (the server still checks reach). */
    public void attack(Object entity) {
        Entity e = entity(entity);
        onGame(() -> {
            Minecraft mc = mc0();
            if (mc.player == null) return;
            look(mc.player, e.getBoundingBox().getCenter());
            ((MinecraftInvoker) mc).mcplink$pick(1.0F);
            mc.missTime = 0;
            ((MinecraftInvoker) mc).mcplink$startAttack();
        });
    }

    /** Left-click on whatever the crosshair is on. */
    public void click() {
        onGame(() -> {
            Minecraft mc = mc0();
            if (mc.player == null) return;
            ((MinecraftInvoker) mc).mcplink$pick(1.0F);
            mc.missTime = 0;
            ((MinecraftInvoker) mc).mcplink$startAttack();
        });
    }

    /** Right-click on whatever the crosshair is on (place, open, use the held item). */
    public void use() {
        onGame(() -> {
            Minecraft mc = mc0();
            if (mc.player == null) return;
            ((MinecraftInvoker) mc).mcplink$pick(1.0F);
            ((MinecraftInvoker) mc).mcplink$startUseItem();
        });
    }

    /** Turns to an entity and right-clicks it (trade with a villager, ride, feed...). */
    public void useOn(Object entity) {
        Entity e = entity(entity);
        onGame(() -> {
            Minecraft mc = mc0();
            if (mc.player == null) return;
            look(mc.player, e.getBoundingBox().getCenter());
            ((MinecraftInvoker) mc).mcplink$pick(1.0F);
            if (mc.hitResult instanceof EntityHitResult) ((MinecraftInvoker) mc).mcplink$startUseItem();
        });
    }

    /** Sends a chat message as the player (cleaned up and cut to 256 characters, like the chat screen does). */
    public void say(String text) {
        String clean = StringUtil.trimChatMessage(StringUtil.filterText(StringUtils.normalizeSpace(text.trim())));
        if (clean.isEmpty()) return;
        onGame(() -> {
            if (mc0().getConnection() != null) mc0().getConnection().sendChat(clean);
        });
    }

    /** Runs a command as the player, with or without the leading slash. */
    public void command(String cmd) {
        String clean = StringUtil.filterText(StringUtils.normalizeSpace(cmd.trim()));
        String c = clean.startsWith("/") ? clean.substring(1) : clean;
        if (c.isEmpty()) return;
        onGame(() -> {
            if (mc0().getConnection() != null) mc0().getConnection().sendCommand(c);
        });
    }

    /** Sends any packet to the server. Build one with game.type("net.minecraft.network.protocol.game.Serverbound...") */
    public void send(Object packet) {
        Object p = Scripts.unwrap(packet);
        if (!(p instanceof Packet<?> pk)) throw new IllegalArgumentException("Not a packet: " + p);
        onGame(() -> {
            if (mc0().getConnection() != null) mc0().getConnection().send(pk);
        });
    }

    // ---------------------------------------------------------------- reaching anything

    /** A Java class by name, usable for statics and `new`: game.type("net.minecraft.world.entity.animal.goat.Goat"). */
    public Object type(String className) throws ClassNotFoundException {
        Class<?> c = Class.forName(className, true, ScriptApi.class.getClassLoader());
        return new NativeJavaClass(script.scope, c);
    }

    /** Reads a path of fields/getters from an object, private ones included: game.get(mc, "gui.hud.chat"). */
    public Object get(Object target, String path) {
        return Reflect.walk(Scripts.unwrap(target), path);
    }

    /** Calls any method by name, private ones included, choosing the overload that fits the arguments. */
    public Object call(Object target, String method, Object... args) throws Exception {
        return Reflect.invoke(Scripts.unwrap(target), method, args == null ? new Object[0] : args);
    }

    // ---------------------------------------------------------------- HUD drawing for "hud" handlers

    /** Passed to "hud" handlers each frame. Colours are 0xRRGGBB (or 0xAARRGGBB for fill). */
    public static final class Draw {
        private final GuiGraphicsExtractor g;
        /** First free line under the MCP Link tags. */
        public final int top;

        Draw(GuiGraphicsExtractor g, int top) {
            this.g = g;
            this.top = top;
        }

        public void text(String text, int x, int y, int color) {
            g.text(mc0().font, text, x, y, 0xFF000000 | color, true);
        }

        public void fill(int x1, int y1, int x2, int y2, int argb) {
            g.fill(x1, y1, x2, y2, argb);
        }

        public int width(String text) {
            return mc0().font.width(text);
        }

        public int screenWidth() {
            return mc0().getWindow().getGuiScaledWidth();
        }

        public int screenHeight() {
            return mc0().getWindow().getGuiScaledHeight();
        }
    }
}
