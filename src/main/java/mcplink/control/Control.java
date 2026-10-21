package mcplink.control;

import mcplink.McpLink;
import mcplink.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Input;

/**
 * Whether the agent may act, and what it is doing. Everything here runs on the client thread:
 * tools hand work over with {@link #run(Task)} and the task is ticked from END_CLIENT_TICK.
 */
public final class Control {
    /** A multi-tick action. Returning a non-null string ends it with that result. */
    public interface Task {
        String name();

        /** @return null to keep going, otherwise a short outcome ("done", "stuck: ...") */
        String tick(Minecraft mc);

        default void stop(Minecraft mc) {
        }
    }

    private static volatile boolean enabled;
    private static volatile Task task;
    private static volatile String lastOutcome;
    private static volatile String lastTaskName;

    /** Movement keys a task wants held this tick; read by the KeyboardInput mixin. */
    public static boolean fwd, back, left, right, jump, sneak, sprint;
    private static boolean steering;
    /** A mining task wants the attack held; read by the continueAttack mixin. */
    private static boolean attack;

    private Control() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void toggle() {
        enabled = !enabled;
        Minecraft mc = Minecraft.getInstance();
        if (!enabled) cancel(mc, "control switched off");
        message(mc, enabled ? "MCP control ON (" + McpLink.controlKeyName() + " to stop)" : "MCP control OFF");
    }

    /** Leaving or joining a world always hands control back. Client thread only. */
    public static void switchOff(Minecraft mc) {
        if (!enabled && task == null) return;
        enabled = false;
        cancel(mc, "left the world");
    }

    /** While control is on, the game keeps running when the window loses focus (see the pauseIfInactive mixin). */
    public static boolean keepRunning() {
        return enabled && Config.get().keepRunningWithControl;
    }

    private static void message(Minecraft mc, String text) {
        if (mc.player != null && Config.get().controlMessages) mc.player.sendOverlayMessage(Component.literal(text));
    }

    public static String activity() {
        Task t = task;
        return t == null ? null : t.name();
    }

    public static String lastOutcome() {
        return lastTaskName == null ? null : lastTaskName + ": " + lastOutcome;
    }

    /** Starts a task, replacing any current one. Client thread only. */
    public static void run(Task t) {
        Minecraft mc = Minecraft.getInstance();
        if (task != null) finish(mc, "replaced by " + t.name());
        task = t;
        lastTaskName = t.name();
        lastOutcome = "running";
        unpause(mc);
    }

    public static void cancel(Minecraft mc, String why) {
        if (task != null) finish(mc, why);
        releaseKeys();
    }

    private static void finish(Minecraft mc, String outcome) {
        Task t = task;
        task = null;
        try {
            t.stop(mc);
        } catch (Exception e) {
            McpLink.LOG.warn("Task stop failed", e);
        }
        releaseKeys();
        lastOutcome = outcome;
    }

    public static boolean busy() {
        return task != null;
    }

    public static void releaseKeys() {
        fwd = back = left = right = jump = sneak = sprint = false;
        steering = false;
        attack = false;
    }

    public static void holdAttack(boolean on) {
        attack = on;
    }

    public static boolean holdAttack() {
        return attack && enabled;
    }

    public static void steer() {
        steering = true;
    }

    public static boolean steering() {
        return steering && enabled;
    }

    /** Singleplayer pauses when the window loses focus; actions only make progress with the pause menu closed. */
    public static void unpause(Minecraft mc) {
        if (mc.gui.screen() instanceof PauseScreen) {
            mc.gui.setScreen(null);
        }
    }

    /** The player pressed movement keys while a task was steering: hand control back. */
    public static void userMoved() {
        if (task != null && steering) {
            Minecraft mc = Minecraft.getInstance();
            cancel(mc, "stopped: you moved");
            message(mc, "MCP stopped (you moved)");
        }
    }

    public static Input input() {
        return new Input(fwd, back, left, right, jump, sneak, sprint);
    }

    public static void tick(Minecraft mc) {
        Task t = task;
        if (t == null) return;
        if (!enabled || mc.player == null || mc.level == null) {
            cancel(mc, mc.player == null ? "left the world" : "control off");
            return;
        }
        String out;
        try {
            out = t.tick(mc);
        } catch (Exception e) {
            McpLink.LOG.warn("Task {} failed", t.name(), e);
            out = "error: " + e;
        }
        if (out != null && task == t) finish(mc, out);
    }
}
