package mcplink.state;

import mcplink.Game;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records where chosen entities are over time, so movement (villager pathing, mob flow in a farm) can be studied afterwards. */
public final class Tracker {
    /** Total samples one recording may hold (about 50 MB), however many entities and ticks were asked for. */
    public static final int MAX_SAMPLES = 1_000_000;

    public record Sample(long tick, double x, double y, double z, boolean onGround) {
    }

    public static final class Trail {
        public final int id;
        public final String type;
        public final String name;
        public final List<Sample> samples = new ArrayList<>();
        public boolean gone;

        Trail(int id, String type, String name) {
            this.id = id;
            this.type = type;
            this.name = name;
        }
    }

    public static final class Session {
        public final Set<String> types;
        public final Set<Integer> ids;
        public final AABB area;
        public final int every;
        public final long endTick;
        public final int maxEntities;
        public long ticks;
        public int sampleCount;
        /** True once MAX_SAMPLES was reached and recording stopped early. */
        public boolean full;
        public final Map<Integer, Trail> trails = new LinkedHashMap<>();

        Session(Set<String> types, Set<Integer> ids, AABB area, int every, long endTick, int maxEntities) {
            this.types = types;
            this.ids = ids;
            this.area = area;
            this.every = every;
            this.endTick = endTick;
            this.maxEntities = maxEntities;
        }

        public boolean running() {
            return ticks < endTick && !full;
        }
    }

    private static Session session;

    private Tracker() {
    }

    public static synchronized Session start(Set<String> types, Set<Integer> ids, AABB area, int every, int seconds, int maxEntities) {
        session = new Session(types, ids, area, Math.max(1, every), seconds * 20L, maxEntities);
        return session;
    }

    public static synchronized Session current() {
        return session;
    }

    public static synchronized void stop() {
        if (session != null && session.running()) {
            session.ticks = session.endTick;
        }
    }

    /** Forgets the recording (entity ids mean other entities on another server or world). */
    public static synchronized void clear() {
        session = null;
    }

    public static synchronized void tick(Minecraft mc) {
        Session s = session;
        if (s == null || !s.running() || mc.level == null) return;
        if (s.ticks++ % s.every != 0) return;
        Map<Integer, Boolean> seen = new HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            boolean want = s.ids.contains(e.getId()) || (!s.types.isEmpty() && s.types.contains(Game.id(e)));
            if (!want) continue;
            if (s.area != null && !s.area.contains(e.position())) continue;
            Trail t = s.trails.get(e.getId());
            if (t == null) {
                if (s.trails.size() >= s.maxEntities) continue;
                t = new Trail(e.getId(), Game.id(e), e.hasCustomName() ? e.getCustomName().getString() : null);
                s.trails.put(e.getId(), t);
            }
            if (s.sampleCount >= MAX_SAMPLES) {
                s.full = true;
                return;
            }
            t.samples.add(new Sample(s.ticks - 1, e.getX(), e.getY(), e.getZ(), e.onGround()));
            s.sampleCount++;
            t.gone = false;
            seen.put(e.getId(), true);
        }
        for (Trail t : s.trails.values()) {
            if (!seen.containsKey(t.id)) t.gone = true;
        }
    }
}
