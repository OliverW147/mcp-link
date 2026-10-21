package mcplink.script;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** One saved script: its source and settings, and while it runs, its scope, handlers and timers. */
final class Script {
    record Handler(int id, Script script, String event, Function fn, Pattern filter) {
    }

    static final class Timer {
        final int id;
        final int interval;   // 0 = once
        final Function fn;
        long next;

        Timer(int id, int interval, Function fn, long next) {
            this.id = id;
            this.interval = interval;
            this.fn = fn;
            this.next = next;
        }
    }

    final String name;
    String description = "";
    String source = "";
    boolean enabled = true;
    /** A one-off script_eval: it may not register handlers or timers. */
    final boolean eval;

    /** Held while any of this script's code runs, so its handlers never run at the same time on two threads. */
    final ReentrantLock lock = new ReentrantLock();
    volatile boolean running;
    volatile String status = "stopped";
    Scriptable scope;
    final List<Handler> handlers = new CopyOnWriteArrayList<>();
    final List<Timer> timers = new CopyOnWriteArrayList<>();
    int timeouts;
    int errorsInRow;
    long calls;
    long nanos;

    Script(String name, boolean eval) {
        this.name = name;
        this.eval = eval;
    }
}
