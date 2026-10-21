package mcplink.tools;

import mcplink.Game;
import mcplink.J;
import mcplink.mcp.Args;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mcp.ToolRegistry.ToolError;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Where a mob could naturally spawn, judged with the game's own placement and collision checks plus its light rule. */
public final class SpawnTools {
    private SpawnTools() {
    }

    /** Monsters whose spawn rule ignores light. */
    private static final Set<String> ANY_LIGHT = Set.of("minecraft:blaze", "minecraft:magma_cube", "minecraft:piglin", "minecraft:hoglin",
            "minecraft:zombified_piglin", "minecraft:ghast", "minecraft:piglin_brute", "minecraft:slime", "minecraft:guardian",
            "minecraft:elder_guardian", "minecraft:endermite", "minecraft:shulker", "minecraft:breeze", "minecraft:creaking");

    public static void register(ToolRegistry t) {
        t.add("spawn_check", "Find positions in a box (or radius) where a mob type could naturally spawn, using the game's own spawn "
                + "placement (valid floor, empty/non-dangerous feet and head blocks), the mob's collision box, and its light rule "
                + "(monster: dimension light rules incl. the sky-light roll; animal: grass-type floor and light > 8; none). Each spot gets a "
                + "chance factor from light alone. Also splits spots by distance to the player (no spawns within 24, instant despawn past 128). "
                + "NOT checked (the client cannot know them): the biome's spawn list, structure spawn lists (fortress, monument, hut), mob "
                + "caps, pack spawning, peaceful. Use map=true for an ASCII layer view.",
                Schema.of().area(24).str("mob", "Entity type id (default minecraft:zombie)")
                        .str("rule", "auto (default), monster, animal or none")
                        .str("time", "Sky darkness to assume: now (default), night or day")
                        .integer("limit", "Max positions to list (default 50)").bool("map", "Also return an ASCII map per Y layer")
                        .num("px", "Reference point X for spawn-distance bands (default player)").num("py", "Reference Y").num("pz", "Reference Z"),
                a -> Game.heavy(() -> check(a)));
    }

    private record Spot(int x, int y, int z, double chance, double dist) {
    }

    private static JsonObject check(Args a) throws Exception {
        String mobId = Game.normId(a.s("mob", "minecraft:zombie"));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(mobId))
                .orElseThrow(() -> new ToolError("Unknown entity type " + mobId));
        String rule = a.s("rule", "auto").toLowerCase(java.util.Locale.ROOT);
        if (rule.equals("auto")) {
            if (type.getCategory() == MobCategory.MONSTER) rule = ANY_LIGHT.contains(mobId) ? "none" : "monster";
            else if (type.getCategory() == MobCategory.CREATURE && SpawnPlacements.getPlacementType(type) == net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND) rule = "animal";
            else rule = "none";
        }
        final String lightRule = rule;
        String time = a.s("time", "now").toLowerCase(java.util.Locale.ROOT);
        Game.Box box = Game.onClient(() -> Game.area(a, 24));
        if (box.volume() > 4_000_000) throw new ToolError("Box is " + box.volume() + " blocks; max 4000000 for spawn_check.");
        Vec3 ref = Game.onClient(() -> {
            Vec3 p = Game.player().position();
            return new Vec3(a.d("px", p.x), a.d("py", p.y), a.d("pz", p.z));
        });

        // Off-thread prefilter on block states: feet block without full collision over a block with some collision (or water/lava for swimmers).
        Scan.Snapshot snap = Scan.snapshot(new Game.Box(box.x1(), Math.max(box.y1() - 1, -2048), box.z1(), box.x2(), box.y2() + 2, box.z2()), 4_500_000);
        List<int[]> cand = new ArrayList<>();
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int z = box.z1(); z <= box.z2(); z++) {
                if (!snap.loaded(x, z)) continue;
                for (int y = box.y1(); y <= box.y2(); y++) {
                    BlockState feet = snap.get(x, y, z);
                    if (feet == null) continue;
                    BlockState below = snap.get(x, y - 1, z);
                    if (below == null) continue;
                    boolean swimmer = SpawnPlacements.getPlacementType(type) != net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND;
                    if (!swimmer && (below.isAir() || !feet.getFluidState().isEmpty())) continue;
                    if (!swimmer && feet.canOcclude() && !feet.isAir()) continue;
                    cand.add(new int[]{x, y, z});
                }
            }
        }

        int[] reasons = new int[3];   // placement, collision, light
        List<Spot> spots = new ArrayList<>();
        record Batch(List<Spot> spots, int[] reasons) {
        }
        // what needs the game thread is read once; the per-spot checks then run here (blocks, collisions and light are safe to read)
        Object[] env = Game.onClient(() -> {
            ClientLevel lvl = Game.level();
            // during thunder the game judges darkness with a fixed sky darkening of 10, whatever the time
            int dark = lvl.isThundering() ? 10 : switch (time) {
                case "night" -> 11;
                case "day" -> 0;
                default -> lvl.getSkyDarken();
            };
            return new Object[]{lvl, dark, lvl.isThundering()};
        });
        ClientLevel level = (ClientLevel) env[0];
        int skyDarken = (Integer) env[1];
        boolean thundering = (Boolean) env[2];
        DimensionType dt = level.dimensionType();
        int batch = 20_000;
        for (int from = 0; from < cand.size(); from += batch) {
            final int f = from, to = Math.min(cand.size(), from + batch);
            // counters live in the batch, so a batch that is retried after a concurrent change does not count twice
            java.util.concurrent.Callable<Batch> work = () -> {
                List<Spot> out = new ArrayList<>();
                int[] rejected = new int[3];
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                for (int i = f; i < to; i++) {
                    int[] c = cand.get(i);
                    m.set(c[0], c[1], c[2]);
                    if (!SpawnPlacements.isSpawnPositionOk(type, level, m)) {
                        rejected[0]++;
                        continue;
                    }
                    if (!level.noBlockCollision(null, type.getSpawnAABB(c[0] + 0.5, c[1], c[2] + 0.5))) {
                        rejected[1]++;
                        continue;
                    }
                    double chance = lightChance(level, dt, m, lightRule, skyDarken);
                    if (chance <= 0) {
                        rejected[2]++;
                        continue;
                    }
                    double dx = c[0] + 0.5 - ref.x, dy = c[1] - ref.y, dz = c[2] + 0.5 - ref.z;
                    out.add(new Spot(c[0], c[1], c[2], chance, Math.sqrt(dx * dx + dy * dy + dz * dz)));
                }
                return new Batch(out, rejected);
            };
            Batch b = Game.safely(work);
            spots.addAll(b.spots());
            for (int i = 0; i < reasons.length; i++) reasons[i] += b.reasons()[i];
        }

        int near = 0, band = 0, far = 0;
        double weighted = 0;
        Map<Integer, int[]> byY = new TreeMap<>();
        for (Spot s : spots) {
            if (s.dist() <= 24) near++;
            else if (s.dist() > 128) far++;
            else band++;
            weighted += s.chance();
            byY.computeIfAbsent(s.y(), k -> new int[1])[0]++;
        }
        spots.sort((p, q) -> Double.compare(p.dist(), q.dist()));
        JsonArray list = new JsonArray();
        for (Spot s : spots.subList(0, Math.min(spots.size(), a.i("limit", 50)))) {
            list.add(J.arr(s.x(), s.y(), s.z(), J.r(s.chance())));
        }
        JsonObject perY = new JsonObject();
        byY.forEach((y, n) -> perY.addProperty(String.valueOf(y), n[0]));
        J j = J.obj().put("mob", mobId).put("light_rule", lightRule).put("time", time).opt("thunder", thundering ? "thundering: sky darkness 10 is used, as the game does" : null).put("box", box.json())
                .put("spawnable", spots.size()).put("light_weighted", J.r(weighted))
                .put("by_distance", J.obj().put("within_24_no_spawn", near).put("24_to_128", band).put("beyond_128_despawn", far).put("ref", Game.pos(ref)))
                .put("by_y", perY)
                .put("rejected", J.obj().put("placement_or_floor", reasons[0]).put("no_room", reasons[1]).put("too_bright_or_wrong_floor", reasons[2]))
                .put("positions_[x,y,z,chance]", list);
        if (snap.missing > 0) j.put("unloaded_chunks", snap.missing);
        if (a.b("map", false)) j.put("map", spawnMap(box, spots));
        return j.build();
    }

    /** Fraction of spawn attempts at this spot that pass the light rule (1 = always, 0 = never). */
    static double lightChance(ClientLevel level, DimensionType dt, BlockPos pos, String rule, int skyDarken) {
        int sky = level.getBrightness(LightLayer.SKY, pos);
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        switch (rule) {
            case "monster" -> {
                double p1 = 1.0 - Math.min(sky, 32) / 32.0;   // fails when sky light > random(32)
                int limit = dt.monsterSpawnBlockLightLimit();
                if (limit < 15 && block > limit) return 0;
                int raw = Math.max(block, sky - skyDarken);
                IntProvider test = dt.monsterSpawnLightTest();
                int lo = test.minInclusive(), hi = test.maxInclusive();
                double p3 = raw > hi ? 0 : raw <= lo ? 1 : (hi - raw + 1) / (double) (hi - lo + 1);
                return p1 * p3;
            }
            case "animal" -> {
                if (!level.getBlockState(pos.below()).is(BlockTags.ANIMALS_SPAWNABLE_ON)) return 0;
                int raw = Math.max(block, sky);
                return raw > 8 ? 1 : 0;
            }
            default -> {
                return 1;
            }
        }
    }

    private static String spawnMap(Game.Box box, List<Spot> spots) {
        int w = box.x2() - box.x1() + 1, h = box.z2() - box.z1() + 1;
        if (w > 128 || h > 128) return "(map skipped: box wider than 128)";
        Map<Integer, char[][]> layers = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Spot s : spots) {
            char[][] g = layers.computeIfAbsent(s.y(), k -> {
                char[][] n = new char[h][w];
                for (char[] row : n) java.util.Arrays.fill(row, '.');
                return n;
            });
            g[s.z() - box.z1()][s.x() - box.x1()] = s.chance() >= 0.999 ? 'S' : 's';
        }
        if (layers.size() > 64) return "(map skipped: spawnable spots on " + layers.size() + " layers; narrow the Y range)";
        StringBuilder sb = new StringBuilder("S = spawnable, s = spawnable with reduced chance (light), . = not; rows z ")
                .append(box.z1()).append("..").append(box.z2()).append(", columns x ").append(box.x1()).append("..").append(box.x2()).append('\n');
        layers.forEach((y, g) -> {
            sb.append("y=").append(y).append('\n');
            for (char[] row : g) sb.append(row).append('\n');
        });
        return sb.toString();
    }
}
