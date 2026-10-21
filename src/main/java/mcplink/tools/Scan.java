package mcplink.tools;

import mcplink.Game;
import mcplink.mcp.ToolRegistry.ToolError;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Block access for scans: chunk references are taken on the client thread, then read from the calling thread. */
public final class Scan {
    private Scan() {
    }

    static final class Snapshot {
        final Game.Box box;
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        int missing;
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        Snapshot(Game.Box box) {
            this.box = box;
        }

        /** Null when the chunk is not loaded on the client. */
        BlockState get(int x, int y, int z) {
            LevelChunk c = chunks.get(chunkKey(x >> 4, z >> 4));
            if (c == null) return null;
            return c.getBlockState(m.set(x, y, z));
        }

        boolean loaded(int x, int z) {
            return chunks.containsKey(chunkKey(x >> 4, z >> 4));
        }
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * The client never holds chunks further than this from the player (vanilla keeps view distance + 3, at most 35), so a
     * box is only searched for chunks inside it; everything beyond counts as unloaded without being looked up.
     */
    private static final int LOADED_RADIUS = 48;

    /** Takes the chunk references for a box on the client thread. */
    static Snapshot snapshot(Game.Box box, long maxVolume) throws Exception {
        if (box.volume() > maxVolume) {
            throw new ToolError("Box is " + box.volume() + " blocks; the limit for this tool is " + maxVolume + ". Shrink it or split the scan.");
        }
        return Game.onClient(() -> {
            ClientLevel level = Game.level();
            Snapshot s = new Snapshot(box);
            BlockPos p = Game.player().blockPosition();
            int pcx = p.getX() >> 4, pcz = p.getZ() >> 4;
            int fromX = Math.max(box.x1() >> 4, pcx - LOADED_RADIUS), toX = Math.min(box.x2() >> 4, pcx + LOADED_RADIUS);
            int fromZ = Math.max(box.z1() >> 4, pcz - LOADED_RADIUS), toZ = Math.min(box.z2() >> 4, pcz + LOADED_RADIUS);
            for (int cx = fromX; cx <= toX; cx++) {
                for (int cz = fromZ; cz <= toZ; cz++) {
                    LevelChunk c = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (c != null) s.chunks.put(chunkKey(cx, cz), c);
                }
            }
            s.missing = (int) Math.min(Integer.MAX_VALUE, box.chunkColumns() - s.chunks.size());
            return s;
        });
    }

    /**
     * Block filter from specs such as "hopper", "minecraft:stone_slab[type=bottom]", "#minecraft:logs", "*_slab" or "!air".
     * A state matches when it matches any positive spec and no negative ("!") spec.
     */
    public static final class Matcher implements java.util.function.Predicate<BlockState> {
        private final List<java.util.function.Predicate<BlockState>> pos = new ArrayList<>();
        private final List<java.util.function.Predicate<BlockState>> neg = new ArrayList<>();
        private final Map<BlockState, Boolean> cache = new IdentityHashMap<>();

        public Matcher(List<String> specs) {
            for (String raw : specs) {
                String spec = raw.trim();
                boolean not = spec.startsWith("!");
                if (not) spec = spec.substring(1);
                (not ? neg : pos).add(compile(spec));
            }
        }

        boolean empty() {
            return pos.isEmpty() && neg.isEmpty();
        }

        /** Whether any spec names blocks to include (rather than only exclusions). */
        boolean hasPositive() {
            return !pos.isEmpty();
        }

        @Override
        public boolean test(BlockState s) {
            Boolean b = cache.get(s);
            if (b == null) {
                boolean p = pos.isEmpty() || pos.stream().anyMatch(x -> x.test(s));
                boolean n = neg.stream().anyMatch(x -> x.test(s));
                b = p && !n;
                cache.put(s, b);
            }
            return b;
        }

        private static java.util.function.Predicate<BlockState> compile(String spec) {
            String props = null;
            int br = spec.indexOf('[');
            if (br >= 0 && spec.endsWith("]")) {
                props = spec.substring(br + 1, spec.length() - 1);
                spec = spec.substring(0, br);
            }
            java.util.function.Predicate<BlockState> base;
            if (spec.startsWith("#")) {
                String tagId = spec.substring(1).contains(":") ? spec.substring(1) : "minecraft:" + spec.substring(1);
                Identifier ident = Identifier.tryParse(tagId);
                if (ident == null) throw new ToolError("Bad tag " + spec);
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, ident);
                base = s -> s.is(tag);
            } else if (spec.contains("*")) {
                Pattern p = Pattern.compile(Pattern.quote(Game.normId(spec)).replace("*", "\\E.*\\Q"));
                base = s -> p.matcher(Game.id(s)).matches();
            } else {
                String id = Game.normId(spec);
                base = s -> Game.id(s).equals(id);
            }
            if (props == null || props.isBlank()) return base;
            Map<String, String> want = new HashMap<>();
            for (String kv : props.split(",")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2) want.put(p[0].trim(), p[1].trim());
            }
            return s -> {
                if (!base.test(s)) return false;
                for (var e : want.entrySet()) {
                    Property<?> prop = s.getBlock().getStateDefinition().getProperty(e.getKey());
                    if (prop == null || !valueName(s, prop).equals(e.getValue())) return false;
                }
                return true;
            };
        }
    }

    static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    /** Splits "a, b[x=1,y=2], #c" on the commas that are not inside [...] state brackets. */
    public static List<String> splitSpecs(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (char ch : s.toCharArray()) {
            if (ch == '[') depth++;
            if (ch == ']') depth = Math.max(0, depth - 1);
            if (ch == ',' && depth == 0) {
                if (!cur.toString().isBlank()) out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }
}
