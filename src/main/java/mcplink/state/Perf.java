package mcplink.state;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Client frame and tick timings, and the server's tick rate estimated from the time packets it sends
 * (one per second carrying the game time: game ticks advanced over wall time is the real TPS).
 */
public final class Perf {
    /** Enough for the 60 s window at over 1000 FPS. */
    private static final int FRAMES = 1 << 16;
    private static final long[] frameNanos = new long[FRAMES];
    private static int frameIdx, frameCount;
    private static long lastFrame;
    /** Longer gaps are the computer sleeping or a debugger, not a frame. */
    private static final long MAX_FRAME_NANOS = 10_000_000_000L;

    private static final int TICKS = 1200;
    private static final long[] tickNanos = new long[TICKS];
    private static int tickIdx, tickCount;
    private static long tickStart;

    private record TimeSample(long wallNanos, long gameTime) {
    }

    private static final ArrayDeque<TimeSample> TIME = new ArrayDeque<>();

    private Perf() {
    }

    /** Called once per frame from Minecraft.runTick. */
    public static synchronized void frame() {
        long now = System.nanoTime();
        if (lastFrame != 0 && now - lastFrame < MAX_FRAME_NANOS) {
            frameNanos[frameIdx] = now - lastFrame;
            frameIdx = (frameIdx + 1) % FRAMES;
            frameCount = Math.min(frameCount + 1, FRAMES);
        }
        lastFrame = now;
    }

    public static synchronized void tickStart() {
        tickStart = System.nanoTime();
    }

    public static synchronized void tickEnd() {
        if (tickStart == 0) return;
        tickNanos[tickIdx] = System.nanoTime() - tickStart;
        tickIdx = (tickIdx + 1) % TICKS;
        tickCount = Math.min(tickCount + 1, TICKS);
    }

    public static synchronized void serverTime(long gameTime) {
        long now = System.nanoTime();
        TimeSample last = TIME.peekLast();
        if (last != null && gameTime < last.gameTime()) TIME.clear();   // world change or /time set
        TIME.addLast(new TimeSample(now, gameTime));
        while (TIME.size() > 600) TIME.removeFirst();
    }

    public static synchronized void resetServerTime() {
        TIME.clear();
    }

    /** Ticks per second over roughly the last `seconds` seconds, or NaN without enough samples. */
    public static synchronized double tps(int seconds) {
        TimeSample newest = TIME.peekLast();
        if (newest == null) return Double.NaN;
        TimeSample from = null;
        long cutoff = newest.wallNanos() - seconds * 1_000_000_000L;
        for (TimeSample s : TIME) {
            if (s.wallNanos() >= cutoff) {
                from = s;
                break;
            }
        }
        if (from == null || from == newest) return Double.NaN;
        double wall = (newest.wallNanos() - from.wallNanos()) / 1e9;
        return wall < 1.5 ? Double.NaN : (newest.gameTime() - from.gameTime()) / wall;
    }

    /** Seconds since the last time packet (a stalled server stops sending them). */
    public static synchronized double secondsSinceServerTime() {
        TimeSample newest = TIME.peekLast();
        return newest == null ? Double.NaN : (System.nanoTime() - newest.wallNanos()) / 1e9;
    }

    /** [avg fps, 1% low fps, worst frame ms, median frame ms] over the last `seconds` seconds of frames. */
    public static synchronized double[] frames(double seconds) {
        long budget = (long) (seconds * 1e9);
        int n = 0;
        long total = 0;
        while (n < frameCount && total < budget) {
            total += frameNanos[(frameIdx - 1 - n + FRAMES) % FRAMES];
            n++;
        }
        if (n == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        long[] sorted = new long[n];
        for (int i = 0; i < n; i++) sorted[i] = frameNanos[(frameIdx - 1 - i + FRAMES) % FRAMES];
        Arrays.sort(sorted);
        double avgFps = n / (total / 1e9);
        long p99 = sorted[Math.min(n - 1, (int) Math.floor(n * 0.99))];
        return new double[]{avgFps, 1e9 / p99, sorted[n - 1] / 1e6, sorted[n / 2] / 1e6};
    }

    /** [avg ms, max ms] of client ticks over the last `n` ticks. */
    public static synchronized double[] ticks(int n) {
        n = Math.min(n, tickCount);
        if (n == 0) return new double[]{Double.NaN, Double.NaN};
        long sum = 0, max = 0;
        for (int i = 0; i < n; i++) {
            long v = tickNanos[(tickIdx - 1 - i + TICKS) % TICKS];
            sum += v;
            max = Math.max(max, v);
        }
        return new double[]{sum / (double) n / 1e6, max / 1e6};
    }
}
