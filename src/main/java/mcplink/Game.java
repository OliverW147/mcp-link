package mcplink;

import mcplink.mcp.Args;
import mcplink.mcp.ToolRegistry.ToolError;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Running work on the client thread, and the small conversions every tool needs. */
public final class Game {
    private Game() {
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /**
     * Runs on the client thread and waits for the answer (game state must not be read from the HTTP threads). If the game does
     * not get to it within 15 s the work is dropped instead of running late, so a retried action never happens twice.
     */
    public static <T> T onClient(Callable<T> work) throws Exception {
        Minecraft mc = mc();
        if (mc.isSameThread()) return work.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        // 0 = queued, 1 = running, 2 = given up
        AtomicInteger state = new AtomicInteger();
        mc.execute(() -> {
            if (!state.compareAndSet(0, 1)) return;
            try {
                f.complete(work.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            try {
                return f.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                if (state.compareAndSet(0, 2)) {
                    throw new ToolError("The game did not answer within 15 s (frozen, or still loading?); nothing was done.");
                }
                // it started just in time: wait for it, so the caller learns what happened
                return f.get();
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            throw new RuntimeException(c);
        }
    }

    /** Exceptions a read from another thread can hit while the game changes the same data. */
    public static boolean concurrent(Throwable e) {
        return e instanceof java.util.ConcurrentModificationException || e instanceof IndexOutOfBoundsException
                || e instanceof NullPointerException || e instanceof ClassCastException;
    }

    /**
     * Runs on the calling (HTTP) thread instead of the game thread. For reads of data the game only swaps or rarely changes
     * (registries, chunk sections, light, frozen objects). A concurrent change that trips it up is retried here a couple of
     * times, and only then on the client thread, so the game is not held up by a busy moment.
     */
    public static <T> T safely(Callable<T> work) throws Exception {
        try {
            return offThread(work);
        } catch (Exception e) {
            if (!concurrent(e)) throw e;
            return onClient(work);
        }
    }

    /** Like {@link #safely} but never touches the client thread: for work that is too long to run there. */
    public static <T> T offThread(Callable<T> work) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return work.call();
            } catch (Exception e) {
                if (!concurrent(e) || attempt >= 2) throw e;
                Thread.sleep(5);
            }
        }
    }

    private static final java.util.concurrent.Semaphore HEAVY = new java.util.concurrent.Semaphore(1, true);

    /** Big scans run one at a time so several requests never occupy more than one CPU core. */
    public static <T> T heavy(Callable<T> work) throws Exception {
        HEAVY.acquire();
        try {
            return work.call();
        } finally {
            HEAVY.release();
        }
    }

    public static ClientLevel level() {
        ClientLevel l = mc().level;
        if (l == null) throw new ToolError("Not in a world right now.");
        return l;
    }

    public static LocalPlayer player() {
        LocalPlayer p = mc().player;
        if (p == null) throw new ToolError("Not in a world right now.");
        return p;
    }

    public static String id(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    /** Full state string, e.g. minecraft:oak_stairs[facing=north,half=bottom,...]. */
    public static String state(BlockState s) {
        return BlockStateParser.serialize(s);
    }

    public static String id(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    public static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    public static String normId(String id) {
        if (id.startsWith("#")) return id;
        return id.contains(":") ? id : "minecraft:" + id;
    }

    public static JsonElement pos(BlockPos p) {
        return J.arr(p.getX(), p.getY(), p.getZ());
    }

    public static JsonElement pos(Vec3 v) {
        return J.arr(J.r(v.x), J.r(v.y), J.r(v.z));
    }

    /** Item as id + count, plus its non-default components (enchantments, damage, name...) when present. */
    public static JsonElement item(ItemStack st) {
        if (st == null || st.isEmpty()) return null;
        J j = J.obj().put("id", BuiltInRegistries.ITEM.getKey(st.getItem()).toString()).put("count", st.getCount());
        if (!st.getComponentsPatch().isEmpty()) {
            try {
                var ops = level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
                JsonElement full = ItemStack.CODEC.encodeStart(ops, st).result().orElse(null);
                if (full != null && full.isJsonObject() && full.getAsJsonObject().has("components")) {
                    j.put("components", full.getAsJsonObject().get("components"));
                }
            } catch (Exception e) {
                j.put("components", st.getComponentsPatch().toString());
            }
        }
        if (st.isDamageableItem()) j.put("durability", (st.getMaxDamage() - st.getDamageValue()) + "/" + st.getMaxDamage());
        return j.build();
    }

    /** Inclusive block box. */
    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public long volume() {
            return ((long) x2 - x1 + 1) * ((long) y2 - y1 + 1) * ((long) z2 - z1 + 1);
        }

        /** Columns of chunks the box touches. */
        public long chunkColumns() {
            return ((long) (x2 >> 4) - (x1 >> 4) + 1) * ((long) (z2 >> 4) - (z1 >> 4) + 1);
        }

        public boolean contains(int x, int y, int z) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }

        public JsonElement json() {
            return J.arr(x1, y1, z1, x2, y2, z2);
        }
    }

    /** Horizontal coordinates past the world's hard limit are pulled back to it, so box sizes stay meaningful. */
    public static final int WORLD_LIMIT = 30_000_000;

    private static int clampXZ(long v) {
        return (int) Math.max(-WORLD_LIMIT, Math.min(WORLD_LIMIT, v));
    }

    /** Box from x1..z2 corners, or a radius (and dy) around cx/cy/cz or the player. Clamped to the world's height. */
    public static Box area(Args a, int defaultRadius) {
        ClientLevel l = level();
        int minY = l.getMinY(), maxY = l.getMaxY() - 1;
        long x1, y1, z1, x2, y2, z2;
        if (a.has("x1") || a.has("x2")) {
            x1 = a.i("x1");
            y1 = a.i("y1", minY);
            z1 = a.i("z1");
            x2 = a.i("x2");
            y2 = a.i("y2", maxY);
            z2 = a.i("z2");
        } else {
            BlockPos c = player().blockPosition();
            long cx = a.i("cx", c.getX()), cy = a.i("cy", c.getY()), cz = a.i("cz", c.getZ());
            long r = Math.max(0, a.i("radius", defaultRadius));
            long dy = Math.max(0, a.i("dy", (int) Math.min(Integer.MAX_VALUE, r)));
            x1 = cx - r;
            x2 = cx + r;
            z1 = cz - r;
            z2 = cz + r;
            // an explicit y1/y2 still limits the height in radius mode
            y1 = a.has("y1") ? a.i("y1") : cy - dy;
            y2 = a.has("y2") ? a.i("y2") : cy + dy;
        }
        Box b = new Box(clampXZ(Math.min(x1, x2)), (int) Math.max(minY, Math.min(y1, y2)), clampXZ(Math.min(z1, z2)),
                clampXZ(Math.max(x1, x2)), (int) Math.min(maxY, Math.max(y1, y2)), clampXZ(Math.max(z1, z2)));
        if (b.y1 > b.y2) throw new ToolError("The box is outside the world's height (" + minY + ".." + maxY + ").");
        return b;
    }

    public static Entity entity(int id) {
        Entity e = level().getEntity(id);
        if (e == null) throw new ToolError("No entity with id " + id + " is loaded on the client.");
        return e;
    }
}
