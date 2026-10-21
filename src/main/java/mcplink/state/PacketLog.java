package mcplink.state;

import net.minecraft.network.protocol.BundlePacket;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Every packet the server sends this client since connecting: a count per type, and the most recent packets of EACH type
 * (so a rare packet is still there long after a flood of common ones). Bundles are unpacked, so entity spawn data is seen too.
 *
 * Written only from the network thread, as packets arrive and before the game handles them; the game thread never touches
 * it. Each type has its own small lock, so a reader copying one type never holds up packets of another.
 */
public final class PacketLog {
    public record Entry(long seq, long time, String type, Object packet) {
    }

    public record TypeStats(String type, long count, long lastTime, int stored) {
    }

    /** Chunk and light data are large; only a few of each are kept whatever the setting. */
    private static final Pattern BULKY = Pattern.compile("LevelChunk|LightUpdate|ChunksBiomes");
    private static final int BULKY_MAX = 8;

    private static final class Ring {
        final String type;
        final boolean bulky;
        final boolean store;
        long count;
        long lastTime;
        Entry[] buf = new Entry[0];
        int next, size;

        Ring(Class<?> cls) {
            this.type = typeName(cls);
            this.bulky = BULKY.matcher(type).find();
            // a bundle's contents are logged one by one, so the bundle itself is only counted
            this.store = !BundlePacket.class.isAssignableFrom(cls);
        }

        int capacity(int perType) {
            if (!store) return 0;
            return bulky ? Math.min(perType, BULKY_MAX) : perType;
        }

        synchronized void add(long seq, long now, Object packet, int perType) {
            count++;
            lastTime = now;
            int cap = capacity(perType);
            if (cap != buf.length) resize(cap);
            if (cap == 0) return;
            buf[next] = new Entry(seq, now, type, packet);
            next = (next + 1) % cap;
            if (size < cap) size++;
        }

        private void resize(int cap) {
            Entry[] old = ordered();
            buf = new Entry[cap];
            int keep = Math.min(cap, old.length);
            System.arraycopy(old, old.length - keep, buf, 0, keep);
            size = keep;
            next = cap == 0 ? 0 : keep % cap;
        }

        /** Oldest first. Caller holds the lock. */
        private Entry[] ordered() {
            Entry[] out = new Entry[size];
            int start = (next - size + buf.length) % Math.max(1, buf.length);
            for (int i = 0; i < size; i++) out[i] = buf[(start + i) % buf.length];
            return out;
        }

        /** Up to `max` newest entries with seq above `after`, oldest first. */
        synchronized List<Entry> since(long after, int max) {
            List<Entry> out = new ArrayList<>();
            Entry[] all = ordered();
            for (int i = all.length - 1; i >= 0 && out.size() < max; i--) {
                if (all[i].seq() <= after) break;
                out.add(all[i]);
            }
            java.util.Collections.reverse(out);
            return out;
        }

        synchronized TypeStats stats() {
            return new TypeStats(type, count, lastTime, size);
        }
    }

    /** Class name without the package, nested classes kept with their owner: ClientboundMoveEntityPacket.Pos, not just Pos. */
    public static String typeName(Class<?> cls) {
        String n = cls.getName();
        return n.substring(n.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static final ConcurrentHashMap<Class<?>, Ring> RINGS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static volatile long since = System.currentTimeMillis();
    private static volatile WeakReference<Object> connection = new WeakReference<>(null);
    private static volatile int perType = 100;

    private PacketLog() {
    }

    /** Called from the network thread for every clientbound packet; `conn` identifies the connection it came on. */
    public static void received(Object conn, Object packet) {
        if (connection.get() != conn) {
            // a new connection to a server (or a new singleplayer world): start over
            RINGS.clear();
            since = System.currentTimeMillis();
            connection = new WeakReference<>(conn);
        }
        long now = System.currentTimeMillis();
        int cap = perType;
        log(packet, now, cap);
        if (packet instanceof BundlePacket<?> bundle) {
            for (Object sub : bundle.subPackets()) log(sub, now, cap);
        }
    }

    private static void log(Object packet, long now, int cap) {
        Class<?> cls = packet.getClass();
        Ring r = RINGS.get(cls);
        if (r == null) r = RINGS.computeIfAbsent(cls, Ring::new);
        r.add(SEQ.incrementAndGet(), now, packet, cap);
    }

    /** How many packets of each type to keep (0 = counts only). Takes effect as packets of each type arrive. */
    public static void setPerType(int n) {
        perType = Math.max(0, n);
    }

    public static int perType() {
        return perType;
    }

    public static List<TypeStats> stats() {
        List<TypeStats> out = new ArrayList<>();
        for (Ring r : RINGS.values()) out.add(r.stats());
        return out;
    }

    public static long since() {
        return since;
    }

    public static long lastSeq() {
        return SEQ.get();
    }

    /**
     * Stored packets whose type matches (all types when null) and whose seq is above `after`: up to `perTypeLimit` of each
     * type, then the newest `limit` of those overall, oldest first.
     */
    public static List<Entry> recent(long after, Pattern type, int limit, int perTypeLimit) {
        List<Entry> out = new ArrayList<>();
        for (Ring r : RINGS.values()) {
            if (type != null && !type.matcher(r.type).find()) continue;
            out.addAll(r.since(after, perTypeLimit));
        }
        out.sort(Comparator.comparingLong(Entry::seq));
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }
}
