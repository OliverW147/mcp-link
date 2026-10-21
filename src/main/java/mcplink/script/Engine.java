package mcplink.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.WrapFactory;

/**
 * Rhino set up for in-game scripts: modern JavaScript, the game's own class loader (so Packages.net.minecraft... resolves), and
 * the interpreter's instruction counter used as a watchdog, so a script that loops forever is cut off instead of freezing the
 * game.
 */
final class Engine extends ContextFactory {
    static final Engine INSTANCE = new Engine();

    /** Thrown into a running script whose time is up. An Error, so script code can't catch it and carry on. */
    static final class Timeout extends Error {
        Timeout(long ms) {
            super("took longer than " + ms + " ms");
        }
    }

    /** Deadline (System.nanoTime) of the innermost script call on this thread, 0 when none. */
    private static final ThreadLocal<long[]> DEADLINE = ThreadLocal.withInitial(() -> new long[]{0, 0});

    /** Strings, numbers and booleans from Java come back as plain JavaScript values, so "abc" === game.id(e) works. */
    private static final WrapFactory WRAP = new WrapFactory();

    static {
        WRAP.setJavaPrimitiveWrap(false);
    }

    private Engine() {
    }

    @Override
    protected Context makeContext() {
        Context cx = super.makeContext();
        cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
        // the instruction observer only works in the interpreter
        cx.setInterpretedMode(true);
        cx.setInstructionObserverThreshold(2_000);
        cx.setApplicationClassLoader(Engine.class.getClassLoader());
        cx.setMaximumInterpreterStackDepth(1_000);
        cx.setWrapFactory(WRAP);
        return cx;
    }

    @Override
    protected void observeInstructionCount(Context cx, int instructionCount) {
        long[] d = DEADLINE.get();
        if (d[0] != 0 && System.nanoTime() > d[0]) throw new Timeout(d[1]);
    }

    /** Sets a deadline for the call about to run; returns the previous one, to hand back to {@link #restore}. */
    static long[] limit(long ms) {
        long[] d = DEADLINE.get();
        long[] prev = {d[0], d[1]};
        long at = System.nanoTime() + ms * 1_000_000L;
        // a nested call (a handler that triggers another event) never gets more time than the outer one
        if (d[0] == 0 || at < d[0]) {
            d[0] = at;
            d[1] = ms;
        }
        return prev;
    }

    static void restore(long[] prev) {
        long[] d = DEADLINE.get();
        d[0] = prev[0];
        d[1] = prev[1];
    }
}
