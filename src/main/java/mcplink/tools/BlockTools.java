package mcplink.tools;

import mcplink.Game;
import mcplink.J;
import mcplink.mcp.Args;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mcp.ToolRegistry.ToolError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/** Reading blocks: single positions, counts, searches and ASCII layer maps. */
public final class BlockTools {
    private BlockTools() {
    }

    private static final String MATCH_HELP = "Block filters: ids ('hopper', 'minecraft:chest'), states ('stone_slab[type=bottom]'), "
            + "tags ('#minecraft:logs'), globs ('*_slab') and exclusions ('!air'). Comma-separated or a list.";

    public static void register(ToolRegistry t) {
        t.add("block_info", "Everything the client knows about specific blocks: full state, block-entity data the server sent (signs, "
                + "spawners, beds, banners, chests' custom names...), block and sky light, biome, fluid, collision shape.",
                Schema.of().intReq("x", "X").intReq("y", "Y").intReq("z", "Z")
                        .intList("more", "Further positions as a flat list x,y,z,x,y,z..."),
                a -> Game.onClient(() -> blockInfo(a)));

        t.add("blocks_count", "Count block types in a box (or radius around the player). Optional 'match' restricts which blocks count; "
                + "'by_y' adds a per-layer breakdown of the matched blocks. " + MATCH_HELP,
                Schema.of().area(32).strList("match", "Filters (see description); default all non-air")
                        .bool("include_air", "Count air too (default false)").bool("states", "Count full states, not just ids")
                        .bool("by_y", "Per-Y counts of matched blocks").integer("top", "Types to list (default 60)"),
                a -> Game.heavy(() -> count(a)));

        t.add("blocks_find", "Positions of matching blocks in a box (or radius), nearest to the centre first, with the total and the "
                + "bounding box of all matches. " + MATCH_HELP,
                Schema.of().area(32).strList("match", "Filters, required").integer("limit", "Max positions (default 100)")
                        .bool("states", "Include each block's full state"),
                a -> Game.heavy(() -> find(a)));

        t.add("blocks_map", "ASCII picture of a build, one character per block with a legend. axis 'y' (default) prints top-down layers "
                + "(rows run north to south = increasing Z, columns west to east = increasing X), 'x' and 'z' print vertical slices (rows top "
                + "to bottom). mode 'light' prints block light (0-9, A-F) in non-solid cells, 'sky' prints sky light. Max 128 x 128 per "
                + "slice and 64 slices. Best way to understand farms and redstone.",
                Schema.of().area(8).str("axis", "y (top-down layers, default), x or z (vertical slices)")
                        .str("mode", "blocks (default), light or sky").bool("states", "Separate characters per full state (e.g. slab top vs bottom)"),
                a -> Game.heavy(() -> map(a)));
    }

    // ---------------------------------------------------------------- block_info

    private static JsonObject blockInfo(Args a) {
        ClientLevel level = Game.level();
        List<BlockPos> list = new ArrayList<>();
        list.add(new BlockPos(a.i("x"), a.i("y"), a.i("z")));
        List<Integer> more = a.ints("more");
        for (int i = 0; i + 2 < more.size(); i += 3) list.add(new BlockPos(more.get(i), more.get(i + 1), more.get(i + 2)));
        if (list.size() > 64) throw new ToolError("At most 64 positions per call.");
        JsonArray out = new JsonArray();
        for (BlockPos p : list) out.add(one(level, p));
        return list.size() == 1 ? out.get(0).getAsJsonObject() : J.obj().put("blocks", out).build();
    }

    static JsonObject one(ClientLevel level, BlockPos p) {
        J j = J.obj().put("pos", Game.pos(p));
        if (!level.isLoaded(p)) return j.put("loaded", false).build();
        BlockState s = level.getBlockState(p);
        j.put("state", Game.state(s));
        FluidState f = s.getFluidState();
        if (!f.isEmpty()) j.put("fluid", J.obj().put("type", f.getType().toString()).put("source", f.isSource()).put("amount", f.getAmount()));
        VoxelShape col = s.getCollisionShape(level, p);
        j.put("collision", col.isEmpty() ? "none" : s.isCollisionShapeFullBlock(level, p) ? "full" :
                "partial y " + J.r(col.min(net.minecraft.core.Direction.Axis.Y)) + ".." + J.r(col.max(net.minecraft.core.Direction.Axis.Y)));
        j.put("light", J.obj().put("block", level.getBrightness(LightLayer.BLOCK, p)).put("sky", level.getBrightness(LightLayer.SKY, p))
                .put("emits", s.getLightEmission()));
        j.put("biome", level.getBiome(p).unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
        BlockEntity be = level.getBlockEntity(p);
        if (be != null) {
            try {
                j.put("block_entity", be.saveWithoutMetadata(level.registryAccess()).toString());
            } catch (Exception e) {
                j.put("block_entity", be.getClass().getSimpleName());
            }
        }
        return j.build();
    }

    // ---------------------------------------------------------------- count / find

    private static JsonObject count(Args a) throws Exception {
        Game.Box box = Game.onClient(() -> Game.area(a, 32));
        Scan.Snapshot s = Scan.snapshot(box, 16_000_000);
        Scan.Matcher m = new Scan.Matcher(a.strs("match"));
        boolean air = a.b("include_air", false), states = a.b("states", false), byY = a.b("by_y", false);
        Map<String, Long> counts = new HashMap<>();
        Map<Integer, Long> perY = new TreeMap<>();
        long matched = 0;
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int z = box.z1(); z <= box.z2(); z++) {
                if (!s.loaded(x, z)) continue;
                for (int y = box.y1(); y <= box.y2(); y++) {
                    BlockState st = s.get(x, y, z);
                    // air only counts when asked for, or when a filter names it ("!stone" alone does not mean "and air")
                    if (st == null || (!air && st.isAir() && !m.hasPositive()) || !m.test(st)) continue;
                    counts.merge(states ? Game.state(st) : Game.id(st), 1L, Long::sum);
                    matched++;
                    if (byY) perY.merge(y, 1L, Long::sum);
                }
            }
        }
        JsonObject c = new JsonObject();
        counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(a.i("top", 60))
                .forEach(e -> c.addProperty(Game.shortId(e.getKey()), e.getValue()));
        J j = J.obj().put("box", box.json()).put("volume", box.volume()).put("matched", matched).put("types", counts.size()).put("counts", c);
        if (s.missing > 0) j.put("unloaded_chunks", s.missing);
        if (byY) {
            JsonObject py = new JsonObject();
            perY.forEach((y, n) -> py.addProperty(String.valueOf(y), n));
            j.put("by_y", py);
        }
        return j.build();
    }

    private static JsonObject find(Args a) throws Exception {
        if (!a.has("match")) throw Args.bad("'match' is required, e.g. [\"spawner\"] or [\"#minecraft:beds\"].");
        BlockPos centre = Game.onClient(() -> Game.player().blockPosition());
        Game.Box box = Game.onClient(() -> Game.area(a, 32));
        Scan.Snapshot s = Scan.snapshot(box, 64_000_000);
        Scan.Matcher m = new Scan.Matcher(a.strs("match"));
        int limit = Math.max(0, Math.min(2000, a.i("limit", 100)));
        boolean states = a.b("states", false);
        int cx = a.i("cx", centre.getX()), cy = a.i("cy", centre.getY()), cz = a.i("cz", centre.getZ());
        // only the nearest `limit` matches are kept (a max-heap on distance); the rest are just counted, so a big box with a
        // common block costs no memory
        PriorityQueue<long[]> nearest = new PriorityQueue<>((p, q) -> Long.compare(q[3], p[3]));   // x, y, z, dist2
        long total = 0;
        int[] bb = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        Map<net.minecraft.world.level.block.Block, long[]> byBlock = new java.util.IdentityHashMap<>();
        boolean positive = m.hasPositive();
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int z = box.z1(); z <= box.z2(); z++) {
                if (!s.loaded(x, z)) continue;
                for (int y = box.y1(); y <= box.y2(); y++) {
                    BlockState st = s.get(x, y, z);
                    if (st == null || (st.isAir() && !positive) || !m.test(st)) continue;
                    long dx = (long) x - cx, dy = (long) y - cy, dz = (long) z - cz, d2 = dx * dx + dy * dy + dz * dz;
                    total++;
                    if (nearest.size() < limit) {
                        nearest.add(new long[]{x, y, z, d2});
                    } else if (limit > 0 && d2 < nearest.peek()[3]) {
                        nearest.poll();
                        nearest.add(new long[]{x, y, z, d2});
                    }
                    byBlock.computeIfAbsent(st.getBlock(), k -> new long[1])[0]++;
                    bb[0] = Math.min(bb[0], x);
                    bb[1] = Math.min(bb[1], y);
                    bb[2] = Math.min(bb[2], z);
                    bb[3] = Math.max(bb[3], x);
                    bb[4] = Math.max(bb[4], y);
                    bb[5] = Math.max(bb[5], z);
                }
            }
        }
        JsonObject byType = new JsonObject();
        byBlock.entrySet().stream().sorted((p, q) -> Long.compare(q.getValue()[0], p.getValue()[0]))
                .forEach(e -> byType.addProperty(Game.shortId(BuiltInRegistries.BLOCK.getKey(e.getKey()).toString()), e.getValue()[0]));
        List<long[]> hits = new ArrayList<>(nearest);
        hits.sort((p, q) -> Long.compare(p[3], q[3]));
        JsonArray arr = new JsonArray();
        for (long[] h : hits) {
            if (states) {
                BlockState st = s.get((int) h[0], (int) h[1], (int) h[2]);
                arr.add(J.obj().put("pos", J.arr(h[0], h[1], h[2])).put("state", st == null ? "?" : Game.shortId(Game.state(st))).build());
            } else {
                arr.add(J.arr(h[0], h[1], h[2]));
            }
        }
        J j = J.obj().put("box", box.json()).put("total", total).put("by_type", byType);
        if (total > 0) j.put("bounds", J.arr(bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]));
        if (s.missing > 0) j.put("unloaded_chunks", s.missing);
        return j.put("positions", arr).put("truncated", total > hits.size()).build();
    }

    // ---------------------------------------------------------------- map

    private static final String CHARS = "#BCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@$%&*+=?<>{}()^~;:!|/\\'\"`";

    private static JsonElement map(Args a) throws Exception {
        String axis = a.s("axis", "y").toLowerCase(Locale.ROOT);
        String mode = a.s("mode", "blocks").toLowerCase(Locale.ROOT);
        boolean states = a.b("states", false);
        Game.Box box = Game.onClient(() -> Game.area(a, 8));
        int w, h, n;   // columns, rows, slices
        switch (axis) {
            case "y" -> {
                w = box.x2() - box.x1() + 1;
                h = box.z2() - box.z1() + 1;
                n = box.y2() - box.y1() + 1;
            }
            case "x" -> {
                w = box.z2() - box.z1() + 1;
                h = box.y2() - box.y1() + 1;
                n = box.x2() - box.x1() + 1;
            }
            case "z" -> {
                w = box.x2() - box.x1() + 1;
                h = box.y2() - box.y1() + 1;
                n = box.z2() - box.z1() + 1;
            }
            default -> throw Args.bad("axis must be y, x or z");
        }
        if (w > 128 || h > 128) throw new ToolError("Each slice is " + w + " x " + h + "; max 128 x 128.");
        if (n > 64) throw new ToolError(n + " slices; max 64. Narrow the box along the " + axis + " axis.");
        if ((long) w * h * n > 120_000) throw new ToolError("Map would be " + ((long) w * h * n) + " cells; keep it under 120000.");
        if (mode.equals("light") || mode.equals("sky")) {
            ClientLevel lvl = Game.onClient(Game::level);
            return Game.safely(() -> lightMap(lvl, box, axis, mode.equals("sky")));
        }
        Scan.Snapshot s = Scan.snapshot(box, 2_000_000);
        Map<String, Character> legend = new LinkedHashMap<>();
        Map<String, Long> freq = new HashMap<>();
        // first pass: frequency, so the commonest blocks get the plainest characters
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int y = box.y1(); y <= box.y2(); y++) {
                for (int z = box.z1(); z <= box.z2(); z++) {
                    BlockState st = s.get(x, y, z);
                    if (st == null || st.isAir()) continue;
                    freq.merge(states ? Game.state(st) : Game.id(st), 1L, Long::sum);
                }
            }
        }
        int ci = 0;
        for (var e : freq.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList()) {
            legend.put(e.getKey(), ci < CHARS.length() ? CHARS.charAt(ci) : '?');
            ci++;
        }
        StringBuilder out = new StringBuilder();
        out.append("box ").append(box.json()).append(", axis ").append(axis).append('\n');
        switch (axis) {
            case "y" -> out.append("each layer: rows z ").append(box.z1()).append("..").append(box.z2()).append(" (north to south), columns x ")
                    .append(box.x1()).append("..").append(box.x2()).append(" (west to east)\n");
            case "x" -> out.append("each slice: rows y ").append(box.y2()).append("..").append(box.y1()).append(" (top to bottom), columns z ")
                    .append(box.z1()).append("..").append(box.z2()).append('\n');
            default -> out.append("each slice: rows y ").append(box.y2()).append("..").append(box.y1()).append(" (top to bottom), columns x ")
                    .append(box.x1()).append("..").append(box.x2()).append('\n');
        }
        for (int k = 0; k < n; k++) {
            int slice = switch (axis) {
                case "y" -> box.y2() - k;
                case "x" -> box.x1() + k;
                default -> box.z1() + k;
            };
            out.append(axis).append('=').append(slice).append('\n');
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    int x, y, z;
                    switch (axis) {
                        case "y" -> {
                            x = box.x1() + c;
                            z = box.z1() + r;
                            y = slice;
                        }
                        case "x" -> {
                            x = slice;
                            z = box.z1() + c;
                            y = box.y2() - r;
                        }
                        default -> {
                            z = slice;
                            x = box.x1() + c;
                            y = box.y2() - r;
                        }
                    }
                    BlockState st = s.get(x, y, z);
                    if (st == null) out.append(' ');
                    else if (st.isAir()) out.append('.');
                    else out.append(legend.get(states ? Game.state(st) : Game.id(st)));
                }
                out.append('\n');
            }
        }
        out.append("legend: . air, (space) unloaded\n");
        legend.forEach((k, v) -> out.append("  ").append(v).append(' ').append(Game.shortId(k)).append(" (").append(freq.get(k)).append(")\n"));
        return new JsonPrimitive(out.toString());
    }

    private static JsonElement lightMap(ClientLevel level, Game.Box box, String axis, boolean sky) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        StringBuilder out = new StringBuilder();
        out.append("box ").append(box.json()).append(", axis ").append(axis).append(", ").append(sky ? "sky" : "block").append(" light\n");
        out.append("digits 0-9 and A-F = light level in open cells, # = solid (full collision) block, space = unloaded\n");
        int w = axis.equals("x") ? box.z2() - box.z1() + 1 : box.x2() - box.x1() + 1;
        int h = axis.equals("y") ? box.z2() - box.z1() + 1 : box.y2() - box.y1() + 1;
        int n = switch (axis) {
            case "y" -> box.y2() - box.y1() + 1;
            case "x" -> box.x2() - box.x1() + 1;
            default -> box.z2() - box.z1() + 1;
        };
        for (int k = 0; k < n; k++) {
            int slice = switch (axis) {
                case "y" -> box.y2() - k;
                case "x" -> box.x1() + k;
                default -> box.z1() + k;
            };
            out.append(axis).append('=').append(slice).append('\n');
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    switch (axis) {
                        case "y" -> m.set(box.x1() + c, slice, box.z1() + r);
                        case "x" -> m.set(slice, box.y2() - r, box.z1() + c);
                        default -> m.set(box.x1() + c, box.y2() - r, slice);
                    }
                    if (!level.isLoaded(m)) {
                        out.append(' ');
                        continue;
                    }
                    BlockState st = level.getBlockState(m);
                    if (st.isCollisionShapeFullBlock(level, m)) {
                        out.append('#');
                        continue;
                    }
                    int l = level.getBrightness(sky ? LightLayer.SKY : LightLayer.BLOCK, m);
                    out.append(Character.toUpperCase(Character.forDigit(l, 16)));
                }
                out.append('\n');
            }
        }
        return new JsonPrimitive(out.toString());
    }
}
