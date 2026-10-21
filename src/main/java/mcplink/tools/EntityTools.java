package mcplink.tools;

import mcplink.Game;
import mcplink.J;
import mcplink.mcp.Args;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mcp.ToolRegistry.ToolError;
import mcplink.state.Tracker;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Entities as the client sees them: listing, details, movement history and the mobs' own pathfinder. */
public final class EntityTools {
    static final int MAX_TRACKED = 256;

    private EntityTools() {
    }

    public static void register(ToolRegistry t) {
        t.add("entities", "Entities the client is tracking in an area: id, type, position, distance, health, name, and type-specific facts "
                + "(villager profession/level, item stacks, XP orb values, babies, sleeping spots, riders). Also counts by type and by mob "
                + "category. Note: the server only sends entities within its tracking range of you (often 48-128 blocks).",
                Schema.of().area(64).strList("types", "Entity ids or globs, e.g. villager, *zombie*").str("category",
                                "monster, creature, ambient, water_creature, water_ambient, underground_water_creature, axolotls or misc")
                        .integer("limit", "Max entities to list (default 100)").bool("players", "Include players (default true)"),
                a -> Game.onClient(() -> list(a)));

        t.add("entity_info", "Everything the client has for one entity: its NBT as the client would save it (position, motion, equipment, "
                + "custom name, villager data, flags...) and the raw synced data values. Server-only state (AI memories, trades before "
                + "opening, persistence) is not sent to clients.",
                Schema.of().intReq("id", "Entity id (from entities)"), a -> Game.onClient(() -> info(a.i("id"))));

        t.add("track_start", "Start recording positions of entities (by type and/or id, optionally only inside a box) every few ticks for a "
                + "while, to study movement afterwards with track_get (villager pathing, mob flow in farms, item streams). Replaces any "
                + "previous recording.",
                Schema.of().strList("types", "Entity ids to record, e.g. villager").intList("ids", "Specific entity ids")
                        .integer("every", "Sample every N ticks (default 5)").integer("seconds", "How long (default 60, max 1800)")
                        .integer("max_entities", "Cap on tracked entities (default 64, max " + MAX_TRACKED + ")")
                        .integer("x1", "Optional area: inclusive block box corner X").integer("y1", "Y").integer("z1", "Z")
                        .integer("x2", "Other corner X").integer("y2", "Y").integer("z2", "Z"),
                a -> Game.onClient(() -> trackStart(a)));

        t.add("track_get", "Results of the current/last recording: per entity its samples (compressed to points where it moved), total "
                + "distance, start/end, bounding box, and whether it left view.",
                Schema.of().integer("max_points", "Max points per entity (default 60)").integer("min_move", "Only keep points that moved at least this far (blocks, default 0.5)"),
                a -> Game.onClient(() -> trackGet(a)));

        t.add("track_stop", "Stop the current recording (keeps the data for track_get).", Schema.of(), a -> Game.onClient(() -> {
            Tracker.stop();
            return J.obj().put("stopped", true).build();
        }));

        t.add("mob_path", "Run a mob's own pathfinder (the same node evaluator and costs the server uses) from where it stands, or a "
                + "hypothetical mob of a type placed at a start position, to a target. Answers 'can a villager/zombie/iron golem walk "
                + "from A to B, and which way?'. Server-side goals and brain memories are not involved.",
                Schema.of().integer("id", "Existing mob's entity id").str("mob", "Or an entity type id to simulate, e.g. villager")
                        .num("from_x", "Start X for a simulated mob").num("from_y", "Start Y").num("from_z", "Start Z")
                        .intReq("x", "Target X").intReq("y", "Target Y").intReq("z", "Target Z")
                        .integer("reach", "How close counts as arrived (default 1)").integer("max_points", "Max path points to return (default 120)"),
                a -> Game.offThread(() -> path(a)));
    }

    // ---------------------------------------------------------------- list

    static String category(Entity e) {
        return e.getType().getCategory().getSerializedName();
    }

    static JsonObject brief(Entity e, Vec3 ref) {
        J j = J.obj().put("id", e.getId()).put("type", Game.shortId(Game.id(e))).put("pos", Game.pos(e.position()))
                .put("dist", J.r(Math.sqrt(e.position().distanceToSqr(ref))));
        if (e.hasCustomName()) j.put("name", e.getCustomName().getString());
        if (e instanceof LivingEntity le) {
            j.put("hp", J.r(le.getHealth()));
            if (le.isBaby()) j.put("baby", true);
            le.getSleepingPos().ifPresent(bp -> j.put("sleeping_at", Game.pos(bp)));
            var main = Game.item(le.getMainHandItem());
            if (main != null) j.put("holding", main);
        }
        if (e instanceof Mob m) {
            if (m.isNoAi()) j.put("no_ai", true);
            if (m.isAggressive()) j.put("aggressive", true);
            if (m.isLeashed()) j.put("leashed", true);
        }
        if (e instanceof VillagerDataHolder v) {
            VillagerData d = v.getVillagerData();
            j.put("villager", J.obj().put("profession", d.profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"))
                    .put("level", d.level()).put("biome_type", d.type().unwrapKey().map(k -> k.identifier().getPath()).orElse("?")));
        }
        if (e instanceof ItemEntity ie) j.put("item", Game.item(ie.getItem()));
        if (e instanceof ExperienceOrb xp) j.put("xp", xp.getValue());
        if (e.isPassenger()) j.put("riding", e.getVehicle().getId());
        if (!e.getPassengers().isEmpty()) j.put("riders", e.getPassengers().stream().map(Entity::getId).toList());
        return j.build();
    }

    static List<Pattern> typePatterns(List<String> specs) {
        List<Pattern> out = new ArrayList<>();
        for (String s : specs) out.add(Pattern.compile(Pattern.quote(Game.normId(s)).replace("*", "\\E.*\\Q")));
        return out;
    }

    private static JsonObject list(Args a) {
        ClientLevel level = Game.level();
        Game.Box b = Game.area(a, 64);
        AABB box = new AABB(b.x1(), b.y1(), b.z1(), b.x2() + 1, b.y2() + 1, b.z2() + 1);
        List<Pattern> types = typePatterns(a.strs("types"));
        String cat = a.s("category", null);
        boolean players = a.b("players", true);
        Vec3 ref = Game.player().position();
        List<Entity> found = new ArrayList<>();
        Map<String, Integer> byType = new HashMap<>(), byCat = new HashMap<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!box.contains(e.position())) continue;
            if (!players && e instanceof net.minecraft.world.entity.player.Player) continue;
            if (cat != null && !category(e).equalsIgnoreCase(cat)) continue;
            String id = Game.id(e);
            if (!types.isEmpty() && types.stream().noneMatch(p -> p.matcher(id).matches())) continue;
            found.add(e);
            byType.merge(Game.shortId(id), 1, Integer::sum);
            byCat.merge(category(e), 1, Integer::sum);
        }
        found.sort((x, y) -> Double.compare(x.position().distanceToSqr(ref), y.position().distanceToSqr(ref)));
        int limit = Math.min(1000, a.i("limit", 100));
        JsonArray arr = new JsonArray();
        for (Entity e : found.subList(0, Math.min(limit, found.size()))) arr.add(brief(e, ref));
        return J.obj().put("box", b.json()).put("total", found.size()).put("by_type", InfoTools.topMap(byType, 50))
                .put("by_category", InfoTools.topMap(byCat, 10)).put("entities", arr).put("truncated", found.size() > limit).build();
    }

    // ---------------------------------------------------------------- info

    private static JsonObject info(int id) {
        Entity e = Game.entity(id);
        J j = J.obj().put("summary", brief(e, Game.player().position())).put("category", category(e))
                .put("bounding_box", J.arr(J.r(e.getBbWidth()), J.r(e.getBbHeight()))).put("on_ground", e.onGround())
                .put("velocity", Game.pos(e.getDeltaMovement())).put("yaw", J.r(e.getYRot())).put("pose", e.getPose().name().toLowerCase(java.util.Locale.ROOT));
        try {
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, Game.level().registryAccess());
            e.saveWithoutId(out);
            j.put("nbt", out.buildResult().toString());
        } catch (Exception ex) {
            j.put("nbt_error", ex.toString());
        }
        List<SynchedEntityData.DataValue<?>> data = e.getEntityData().getNonDefaultValues();
        if (data != null) {
            JsonObject sd = new JsonObject();
            for (var dv : data) sd.addProperty(String.valueOf(dv.id()), String.valueOf(dv.value()));
            j.put("synced_data", sd);
        }
        return j.build();
    }

    // ---------------------------------------------------------------- tracking

    private static JsonObject trackStart(Args a) {
        Set<String> types = new HashSet<>();
        for (String s : a.strs("types")) types.add(Game.normId(s));
        Set<Integer> ids = new HashSet<>(a.ints("ids"));
        if (types.isEmpty() && ids.isEmpty()) throw Args.bad("Give 'types' and/or 'ids' to record.");
        AABB area = null;
        if (a.has("x1")) {
            // an inclusive block box, like every other tool's: the far corner block is inside
            int x1 = a.i("x1"), y1 = a.i("y1"), z1 = a.i("z1"), x2 = a.i("x2"), y2 = a.i("y2"), z2 = a.i("z2");
            area = new AABB(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1);
        }
        int seconds = Math.max(1, Math.min(1800, a.i("seconds", 60)));
        int every = Math.max(1, a.i("every", 5));
        int max = Math.max(1, Math.min(MAX_TRACKED, a.i("max_entities", 64)));
        Tracker.start(types, ids, area, every, seconds, max);
        return J.obj().put("recording", true).put("seconds", seconds).put("every_ticks", every).put("max_entities", max).build();
    }

    private static JsonObject trackGet(Args a) {
        Tracker.Session s = Tracker.current();
        if (s == null) throw new ToolError("Nothing recorded yet; call track_start first.");
        int maxPoints = Math.max(2, a.i("max_points", 60));
        double minMove = a.d("min_move", 0.5);
        JsonArray arr = new JsonArray();
        for (Tracker.Trail t : s.trails.values()) {
            if (t.samples.isEmpty()) continue;
            List<Tracker.Sample> pts = new ArrayList<>();
            double dist = 0;
            Tracker.Sample prev = null, kept = null;
            double[] bb = {1e9, 1e9, 1e9, -1e9, -1e9, -1e9};
            for (Tracker.Sample p : t.samples) {
                if (prev != null) dist += Math.sqrt(sq(p.x() - prev.x()) + sq(p.y() - prev.y()) + sq(p.z() - prev.z()));
                prev = p;
                bb[0] = Math.min(bb[0], p.x());
                bb[1] = Math.min(bb[1], p.y());
                bb[2] = Math.min(bb[2], p.z());
                bb[3] = Math.max(bb[3], p.x());
                bb[4] = Math.max(bb[4], p.y());
                bb[5] = Math.max(bb[5], p.z());
                if (kept == null || Math.sqrt(sq(p.x() - kept.x()) + sq(p.y() - kept.y()) + sq(p.z() - kept.z())) >= minMove) {
                    pts.add(p);
                    kept = p;
                }
            }
            Tracker.Sample last = t.samples.get(t.samples.size() - 1);
            if (pts.get(pts.size() - 1) != last) pts.add(last);
            if (pts.size() > maxPoints) {   // even thinning, keep both ends
                List<Tracker.Sample> thin = new ArrayList<>();
                for (int i = 0; i < maxPoints; i++) thin.add(pts.get((int) Math.round(i * (pts.size() - 1) / (double) (maxPoints - 1))));
                pts = thin;
            }
            JsonArray path = new JsonArray();
            for (Tracker.Sample p : pts) path.add(J.arr(p.tick() / 20.0, J.r(p.x()), J.r(p.y()), J.r(p.z())));
            arr.add(J.obj().put("id", t.id).put("type", Game.shortId(t.type)).opt("name", t.name).put("samples", t.samples.size())
                    .put("distance", J.r(dist)).put("bounds", J.arr(J.r(bb[0]), J.r(bb[1]), J.r(bb[2]), J.r(bb[3]), J.r(bb[4]), J.r(bb[5])))
                    .opt("gone", t.gone).put("path_[seconds,x,y,z]", path).build());
        }
        return J.obj().put("running", s.running()).put("elapsed_s", s.ticks / 20.0).put("length_s", s.endTick / 20.0)
                .opt("stopped_early", s.full ? "the recording reached " + Tracker.MAX_SAMPLES + " samples" : null)
                .put("entities", arr).build();
    }

    private static double sq(double d) {
        return d * d;
    }

    // ---------------------------------------------------------------- pathfinding

    private static JsonObject path(Args a) {
        ClientLevel level = Game.level();
        Mob mob;
        boolean simulated = false;
        if (a.has("id")) {
            if (!(Game.entity(a.i("id")) instanceof Mob m)) throw new ToolError("Entity " + a.i("id") + " is not a mob with a pathfinder.");
            mob = m;
        } else {
            String id = Game.normId(a.s("mob", "minecraft:villager"));
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id)).orElseThrow(() -> new ToolError("Unknown entity type " + id));
            Entity e = type.create(level, EntitySpawnReason.LOAD);
            if (!(e instanceof Mob m)) throw new ToolError(id + " is not a mob with a pathfinder.");
            Vec3 p = Game.player().position();
            m.setPos(a.d("from_x", p.x), a.d("from_y", p.y), a.d("from_z", p.z));
            m.setOnGround(true);
            mob = m;
            simulated = true;
        }
        BlockPos target = new BlockPos(a.i("x"), a.i("y"), a.i("z"));
        long t0 = System.nanoTime();
        Path path;
        // the pathfinder keeps its working state in the mob's navigation, so two searches for one mob must not overlap
        synchronized (mob) {
            path = mob.getNavigation().createPath(target, a.i("reach", 1));
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        J j = J.obj().put("mob", Game.shortId(Game.id(mob))).put("simulated", simulated).put("from", Game.pos(mob.position())).put("target", Game.pos(target))
                .put("follow_range", J.r(mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE))).put("ms", J.r(ms));
        if (path == null) {
            return j.put("path", null).put("note", "No path (the mob must be on the ground or in water, and the target within its follow range).").build();
        }
        int max = a.i("max_points", 120);
        JsonArray nodes = new JsonArray();
        int n = path.getNodeCount();
        for (int i = 0; i < n; i++) {
            if (n > max && i % Math.max(1, n / max) != 0 && i != n - 1) continue;
            Node nd = path.getNode(i);
            nodes.add(J.arr(nd.x, nd.y, nd.z, nd.type.name().toLowerCase(java.util.Locale.ROOT)));
        }
        Node end = path.getEndNode();
        return j.put("reaches_target", path.canReach()).put("nodes", n).put("end", end == null ? null : J.arr(end.x, end.y, end.z))
                .put("dist_end_to_target", J.r(path.getDistToTarget())).put("path_[x,y,z,node_type]", nodes).build();
    }
}
