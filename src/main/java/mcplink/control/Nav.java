package mcplink.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Walking to a position: A* over standing spots (walk, diagonal walk, one-block jump up, drops of up to 3), then a follower that
 * only turns the player and holds movement keys, so the game's own physics moves it. No digging, building, doors or parkour.
 */
public final class Nav {
    /** Replanning during a walk happens here, so the game thread never runs the search. */
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-link-nav");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private Nav() {
    }

    /** Plans off the game thread; a read that trips over a chunk the game is changing is tried again. */
    static CompletableFuture<Plan> planAsync(ClientLevel l, BlockPos start, BlockPos goal, double tol, int maxNodes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mcplink.Game.offThread(() -> plan(l, start, goal, tol, maxNodes));
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, PLANNER);
    }

    public record Plan(List<BlockPos> nodes, boolean complete, int expanded) {
    }

    // ---------------------------------------------------------------- terrain

    private static boolean dangerous(BlockState s) {
        return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE)
                || s.is(Blocks.SOUL_CAMPFIRE) || s.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean open(ClientLevel l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getCollisionShape(l, p).isEmpty() && !dangerous(s);
    }

    private static boolean water(ClientLevel l, BlockPos p) {
        return l.getFluidState(p).is(FluidTags.WATER);
    }

    /** A spot the player can stand in with feet in block p. */
    static boolean standable(ClientLevel l, BlockPos p) {
        if (!l.isLoaded(p)) return false;
        BlockState feet = l.getBlockState(p);
        if (dangerous(feet)) return false;
        VoxelShape fs = feet.getCollisionShape(l, p);
        BlockPos head = p.above();
        if (!open(l, head)) return false;
        if (!fs.isEmpty()) {
            // a low block in the feet cell (slab, carpet, snow layer): stand on top of it, needs room above the head
            if (fs.max(Direction.Axis.Y) > 0.5) return false;
            return fs.max(Direction.Axis.Y) < 0.2 || open(l, head.above());
        }
        if (water(l, p)) return true;   // swimming
        BlockPos below = p.below();
        BlockState b = l.getBlockState(below);
        if (dangerous(b)) return false;
        VoxelShape bs = b.getCollisionShape(l, below);
        if (bs.isEmpty()) return false;
        double top = bs.max(Direction.Axis.Y);
        return top >= 0.8 && top <= 1.0;
    }

    // ---------------------------------------------------------------- planning

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class N implements Comparable<N> {
        final BlockPos p;
        final double g, f;
        final N parent;

        N(BlockPos p, double g, double h, N parent) {
            this.p = p;
            this.g = g;
            this.f = g + h;
            this.parent = parent;
        }

        public int compareTo(N o) {
            return Double.compare(f, o.f);
        }
    }

    private static double h(BlockPos a, BlockPos goal) {
        double dx = a.getX() - goal.getX(), dy = a.getY() - goal.getY(), dz = a.getZ() - goal.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.5;
    }

    static boolean atGoal(BlockPos p, BlockPos goal, double tol) {
        double dx = p.getX() - goal.getX(), dz = p.getZ() - goal.getZ();
        return dx * dx + dz * dz <= tol * tol && Math.abs(p.getY() - goal.getY()) <= Math.max(1, tol);
    }

    public static Plan plan(ClientLevel l, BlockPos start, BlockPos goal, double tol, int maxNodes) {
        PriorityQueue<N> openSet = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        N first = new N(start, 0, h(start, goal), null);
        openSet.add(first);
        best.put(start.asLong(), 0.0);
        N closest = first;
        int expanded = 0;
        while (!openSet.isEmpty() && expanded < maxNodes) {
            N cur = openSet.poll();
            if (cur.g > best.getOrDefault(cur.p.asLong(), Double.MAX_VALUE)) continue;
            expanded++;
            if (atGoal(cur.p, goal, tol)) return new Plan(trace(cur), true, expanded);
            if (h(cur.p, goal) < h(closest.p, goal)) closest = cur;
            for (int[] d : DIRS) {
                boolean diag = d[0] != 0 && d[1] != 0;
                BlockPos side = cur.p.offset(d[0], 0, d[1]);
                if (diag) {
                    // no corner cutting
                    if (!open(l, cur.p.offset(d[0], 0, 0)) || !open(l, cur.p.offset(d[0], 1, 0))
                            || !open(l, cur.p.offset(0, 0, d[1])) || !open(l, cur.p.offset(0, 1, d[1]))) continue;
                }
                double step = diag ? 1.414 : 1.0;
                if (standable(l, side)) {
                    add(openSet, best, cur, side, step + (water(l, side) ? 2 : 0), goal);
                    continue;
                }
                if (!diag) {
                    BlockPos up = side.above();
                    if (standable(l, up) && open(l, cur.p.above(2))) {
                        add(openSet, best, cur, up, step + 1.5, goal);
                    }
                }
                if (open(l, side) && open(l, side.above())) {
                    for (int k = 1; k <= 3; k++) {
                        BlockPos down = side.below(k);
                        if (standable(l, down)) {
                            add(openSet, best, cur, down, step + k * 0.6, goal);
                            break;
                        }
                        if (!open(l, down)) break;
                    }
                }
            }
            if (water(l, cur.p) && standable(l, cur.p.above())) add(openSet, best, cur, cur.p.above(), 2, goal);
        }
        return new Plan(trace(closest), false, expanded);
    }

    private static void add(PriorityQueue<N> openSet, Map<Long, Double> best, N cur, BlockPos p, double cost, BlockPos goal) {
        double g = cur.g + cost;
        long k = p.asLong();
        if (g < best.getOrDefault(k, Double.MAX_VALUE)) {
            best.put(k, g);
            openSet.add(new N(p, g, h(p, goal), cur));
        }
    }

    private static List<BlockPos> trace(N n) {
        List<BlockPos> out = new ArrayList<>();
        for (; n != null; n = n.parent) out.add(n.p);
        Collections.reverse(out);
        return out;
    }

    // ---------------------------------------------------------------- following

    public static final class Walk implements Control.Task {
        private final BlockPos goal;
        private final double tol;
        private final boolean sprint;
        private List<BlockPos> nodes;
        private boolean complete;
        private int index;
        private int ticks, sinceProgress, replans;
        private double bestDist = Double.MAX_VALUE;
        private CompletableFuture<Plan> replanning;
        private ClientLevel planLevel;
        private String replanWhy;
        private int waited;

        public Walk(Plan plan, BlockPos goal, double tol, boolean sprint) {
            this.nodes = plan.nodes();
            this.complete = plan.complete();
            this.goal = goal;
            this.tol = tol;
            this.sprint = sprint;
        }

        public String name() {
            return "walking to " + goal.getX() + " " + goal.getY() + " " + goal.getZ() + " (" + Math.max(0, nodes.size() - 1 - index) + " steps left)";
        }

        public String tick(Minecraft mc) {
            LocalPlayer p = mc.player;
            ClientLevel l = mc.level;
            ticks++;
            Control.releaseKeys();
            Control.steer();
            if (replanning != null) return awaitPlan(mc);
            BlockPos at = p.blockPosition();
            if (atGoal(at, goal, tol) && p.onGround()) return "arrived at " + at.getX() + " " + at.getY() + " " + at.getZ();
            // advance past nodes we are standing on or next to
            for (int i = Math.min(nodes.size() - 1, index + 3); i > index; i--) {
                if (near(p, nodes.get(i), 0.45)) {
                    index = i;
                    break;
                }
            }
            if (index >= nodes.size() - 1) {
                if (complete) return "arrived at " + at.getX() + " " + at.getY() + " " + at.getZ();
                return replan(mc, "end of partial path");
            }
            BlockPos next = nodes.get(index + 1);
            double tx = next.getX() + 0.5, tz = next.getZ() + 0.5;
            double dx = tx - p.getX(), dz = tz - p.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            if (Math.abs(p.getXRot()) > 30) p.setXRot(0);
            Control.fwd = true;
            Control.sprint = sprint && remainingStraight() > 4 && p.getFoodData().getFoodLevel() > 6;
            boolean higher = next.getY() > at.getY() || (next.getY() == at.getY() && !l.getBlockState(next).getCollisionShape(l, next).isEmpty());
            if ((higher && dist < 1.6) || (p.horizontalCollision && p.onGround())) Control.jump = true;
            if (p.isInWater() && next.getY() >= at.getY()) Control.jump = true;
            if (dist < 0.25) Control.fwd = false;
            // progress watch
            double toGoal = Math.sqrt(p.distanceToSqr(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5));
            if (toGoal < bestDist - 0.3) {
                bestDist = toGoal;
                sinceProgress = 0;
            } else if (++sinceProgress > 60) {
                return replan(mc, "no progress");
            }
            if (ticks > 20 * 600) return "gave up: took more than 10 minutes";
            return null;
        }

        private double remainingStraight() {
            if (index + 1 >= nodes.size()) return 0;
            BlockPos a = nodes.get(index), b = nodes.get(Math.min(nodes.size() - 1, index + 1));
            int sx = Integer.signum(b.getX() - a.getX()), sz = Integer.signum(b.getZ() - a.getZ());
            int n = 0;
            for (int i = index + 1; i < nodes.size(); i++) {
                BlockPos c = nodes.get(i - 1), d = nodes.get(i);
                if (Integer.signum(d.getX() - c.getX()) != sx || Integer.signum(d.getZ() - c.getZ()) != sz || d.getY() != c.getY()) break;
                n++;
            }
            return n;
        }

        /** Starts a new search in the background; the player stands still until it is ready. */
        private String replan(Minecraft mc, String why) {
            if (++replans > 4) return "stuck (" + why + ") at " + mc.player.blockPosition().toShortString();
            replanWhy = why;
            waited = 0;
            planLevel = mc.level;
            replanning = planAsync(mc.level, startFor(mc), goal, tol, 60_000);
            return null;
        }

        private String awaitPlan(Minecraft mc) {
            if (!replanning.isDone()) {
                return ++waited > 200 ? "stuck (" + replanWhy + "): planning took more than 10 s" : null;
            }
            Plan plan;
            try {
                plan = replanning.join();
            } catch (Exception e) {
                return "stuck (" + replanWhy + "): planning failed: " + e.getCause();
            } finally {
                replanning = null;
            }
            if (mc.level != planLevel) return "stopped: the world changed";
            if (plan.nodes().size() < 2 && !plan.complete()) return "stuck (" + replanWhy + "), no way forward from " + mc.player.blockPosition().toShortString();
            nodes = plan.nodes();
            complete = plan.complete();
            index = 0;
            sinceProgress = 0;
            bestDist = Double.MAX_VALUE;
            return null;
        }

        private static boolean near(LocalPlayer p, BlockPos n, double r) {
            double dx = n.getX() + 0.5 - p.getX(), dz = n.getZ() + 0.5 - p.getZ();
            return dx * dx + dz * dz < r * r && Math.abs(p.getY() - n.getY()) < 1.2;
        }
    }

    /** Standing spot for the player: their block, or the one above when standing on a low block. */
    public static BlockPos startFor(Minecraft mc) {
        BlockPos at = mc.player.blockPosition();
        if (standable(mc.level, at)) return at;
        if (standable(mc.level, at.above())) return at.above();
        if (standable(mc.level, at.below())) return at.below();
        return at;
    }
}
