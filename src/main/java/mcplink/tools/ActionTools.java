package mcplink.tools;

import mcplink.McpLink;
import mcplink.Game;
import mcplink.J;
import mcplink.control.Control;
import mcplink.control.Nav;
import mcplink.mcp.Args;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mcp.ToolRegistry.ToolError;
import mcplink.mixin.MinecraftInvoker;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.StringUtils;

/** Things the agent can do in game. All of them refuse unless the player has switched control on. */
public final class ActionTools {
    private ActionTools() {
    }

    public static void register(ToolRegistry t) {
        t.add("control_status", "Whether control is on (the player toggles it with the MCP Link key, default " + McpLink.DEFAULT_KEY_NAME + "), what task is "
                + "running and how the last one ended.", Schema.of(), a -> Game.onClient(() -> J.obj()
                .put("control", Control.enabled()).put("key", McpLink.controlKeyName()).put("task", Control.activity())
                .put("last_task", Control.lastOutcome()).build()));

        t.addAction("chat_send", "Send a chat message, or a command if it starts with '/'. Goes to the server as the player. Cleaned "
                + "up like the chat screen does: line breaks and repeated spaces become one space, characters the server rejects "
                + "(section sign, control characters) are removed, and chat messages are cut to 256 characters.",
                Schema.of().strReq("text", "Message or /command"), a -> Game.onClient(() -> {
                    String raw = a.s("text");
                    // the server kicks for illegal characters, and a chat message over 256 characters can't even be encoded
                    // (the client disconnects itself), so send what the chat screen would have sent
                    String text = StringUtil.filterText(StringUtils.normalizeSpace(raw.trim()));
                    boolean command = text.startsWith("/");
                    if (!command) text = StringUtil.trimChatMessage(text);
                    if (text.isEmpty() || text.equals("/")) throw Args.bad("Nothing left to send after removing characters the server rejects.");
                    if (command && text.length() > 32_000) throw Args.bad("Command is too long (" + text.length() + " characters).");
                    LocalPlayer p = Game.player();
                    if (command) p.connection.sendCommand(text.substring(1));
                    else p.connection.sendChat(text);
                    return J.obj().put("sent", text).opt("changed", !text.equals(raw)).build();
                }));

        t.addAction("look", "Turn the player's view: absolute yaw/pitch, or towards a point (x,y,z) or an entity's eyes.",
                Schema.of().num("yaw", "Yaw degrees (0 = south, 90 = west, 180 = north, -90 = east)").num("pitch", "Pitch degrees (-90 up, 90 down)")
                        .num("x", "Look at X").num("y", "Look at Y").num("z", "Look at Z").integer("entity", "Look at this entity id"),
                a -> Game.onClient(() -> {
                    LocalPlayer p = Game.player();
                    if (a.has("entity")) lookAt(p, Game.entity(a.i("entity")).getEyePosition());
                    else if (a.has("x")) lookAt(p, new Vec3(a.d("x"), a.d("y"), a.d("z")));
                    else setRot(p, (float) a.d("yaw", p.getYRot()), (float) a.d("pitch", p.getXRot()));
                    ((MinecraftInvoker) Minecraft.getInstance()).mcplink$pick(1.0F);
                    return J.obj().put("yaw", J.r(p.getYRot())).put("pitch", J.r(p.getXRot())).put("crosshair", InfoTools.hit(Minecraft.getInstance().hitResult)).build();
                }));

        t.addAction("move", "Hold movement keys for a number of ticks (20 ticks = 1 s). Returns at once; the movement runs in game. "
                + "Pressing any movement key yourself cancels it.",
                Schema.of().bool("forward", "W").bool("back", "S").bool("left", "A").bool("right", "D").bool("jump", "Space")
                        .bool("sneak", "Shift").bool("sprint", "Sprint").integer("ticks", "How long (default 20, max 1200)"),
                a -> Game.onClient(() -> {
                    int ticks = Math.max(0, Math.min(1200, a.i("ticks", 20)));
                    boolean f = a.b("forward", false), b = a.b("back", false), l = a.b("left", false), r = a.b("right", false),
                            j = a.b("jump", false), s = a.b("sneak", false), sp = a.b("sprint", false);
                    Control.run(new Control.Task() {
                        int left = ticks;

                        public String name() {
                            return "holding keys (" + left + " ticks)";
                        }

                        public String tick(Minecraft mc) {
                            if (left-- <= 0) return "done";
                            Control.steer();
                            Control.fwd = f;
                            Control.back = b;
                            Control.left = l;
                            Control.right = r;
                            Control.jump = j;
                            Control.sneak = s;
                            Control.sprint = sp;
                            return null;
                        }
                    });
                    return J.obj().put("holding_for_ticks", ticks).build();
                }));

        t.addAction("stop", "Stop whatever the agent is doing in game (movement, walking, mining, item use) and release all keys.",
                Schema.of(), a -> Game.onClient(() -> {
                    Control.cancel(Minecraft.getInstance(), "stopped by the agent");
                    Minecraft.getInstance().options.keyUse.setDown(false);
                    return J.obj().put("stopped", true).build();
                }));

        t.addAction("hotbar", "Select a hotbar slot (0-8).", Schema.of().intReq("slot", "0-8"), a -> Game.onClient(() -> {
            int s = a.i("slot");
            if (s < 0 || s > 8) throw Args.bad("slot must be 0-8");
            Game.player().getInventory().setSelectedSlot(s);
            return J.obj().put("selected", s).put("item", Game.item(Game.player().getMainHandItem())).build();
        }));

        t.addAction("attack", "Left-click once on what the crosshair targets, exactly like the mouse (hits an entity or starts breaking "
                + "a block). Give 'entity' to turn towards that entity first.",
                Schema.of().integer("entity", "Entity id to face and hit"), a -> Game.onClient(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    LocalPlayer p = Game.player();
                    Control.unpause(mc);
                    if (a.has("entity")) {
                        Entity e = Game.entity(a.i("entity"));
                        lookAt(p, e.getBoundingBox().getCenter());
                    }
                    ((MinecraftInvoker) mc).mcplink$pick(1.0F);
                    JsonObject target = InfoTools.hit(mc.hitResult);
                    mc.missTime = 0;   // set to 10000 while any screen was open
                    ((MinecraftInvoker) mc).mcplink$startAttack();
                    return J.obj().put("clicked", target).build();
                }));

        t.addAction("use", "Right-click like the mouse on what the crosshair targets (place a block, open a door/chest/villager trade, "
                + "use the held item). Optionally face a block/entity first, and hold for N ticks (eating, drawing a bow, blocking).",
                Schema.of().integer("x", "Face this block X first").integer("y", "Y").integer("z", "Z").integer("entity", "Face this entity first")
                        .integer("hold_ticks", "Keep right-click held this long (default 0 = single click)"),
                a -> Game.onClient(() -> use(a)));

        t.addAction("mine", "Break the block at x,y,z: faces it and holds left-click until it breaks (up to 'timeout' seconds). The block "
                + "must be within reach and visible. Pick the right tool first with hotbar.",
                Schema.of().intReq("x", "X").intReq("y", "Y").intReq("z", "Z").integer("timeout", "Seconds (default 20)"),
                a -> Game.onClient(() -> mine(a)));

        t.addAction("place", "Place the held block at x,y,z by right-clicking the face of a neighbouring solid block.",
                Schema.of().intReq("x", "X").intReq("y", "Y").intReq("z", "Z"), a -> Game.onClient(() -> place(a)));

        t.addAction("drop", "Drop the held item (one, or the whole stack with all=true).", Schema.of().bool("all", "Whole stack"),
                a -> Game.onClient(() -> {
                    LocalPlayer p = Game.player();
                    Minecraft.getInstance().gameMode.dropItem(p, a.b("all", false));
                    return J.obj().put("dropped", true).build();
                }));

        t.addAction("swap_hands", "Swap main-hand and offhand items.", Schema.of(), a -> Game.onClient(() -> {
            Game.player().connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            return J.obj().put("swapped", true).build();
        }));

        t.addAction("open_inventory", "Open the player's inventory screen.", Schema.of(), a -> Game.onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = Game.player();
            if (mc.gameMode.isServerControlledInventory()) p.sendOpenInventory();
            else mc.gui.setScreen(new InventoryScreen(p));
            return J.obj().put("opened", true).build();
        }));

        t.addAction("close_screen", "Close the open screen or container.", Schema.of(), a -> Game.onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui.screen() instanceof AbstractContainerScreen<?>) Game.player().closeContainer();
            else mc.gui.setScreen(null);
            return J.obj().put("closed", true).build();
        }));

        t.addAction("click_slot", "Click a slot in the open container, using the slot numbers from 'screen'. type: pickup (normal click; "
                + "button 0 left, 1 right), quick_move (shift-click), swap (button = hotbar 0-8, or 40 for offhand), throw (button 0 one, 1 "
                + "stack), pickup_all (double-click), clone. slot -999 clicks outside the window (drops the carried stack).",
                Schema.of().intReq("slot", "Slot index").integer("button", "Mouse button or hotbar index (default 0)")
                        .str("type", "pickup (default), quick_move, swap, throw, pickup_all, clone, quick_craft"),
                a -> Game.onClient(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> cs)) throw new ToolError("No container screen is open.");
                    AbstractContainerMenu menu = cs.getMenu();
                    ContainerInput type = ContainerInput.valueOf(a.s("type", "pickup").toUpperCase(java.util.Locale.ROOT));
                    int slot = a.i("slot");
                    mc.gameMode.handleContainerInput(menu.containerId, slot, a.i("button", 0), type, Game.player());
                    return J.obj().put("clicked", slot).put("type", type.name().toLowerCase(java.util.Locale.ROOT))
                            .put("carried", Game.item(menu.getCarried())).build();
                }));

        t.addAction("select_trade", "In an open villager trading screen, pick a trade by index (fills the input slots from the inventory "
                + "like clicking it in the list). Then take the result with click_slot slot 2 (type quick_move for a shift-click).",
                Schema.of().intReq("index", "Trade index from 'screen'"), a -> Game.onClient(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> cs) || !(cs.getMenu() instanceof MerchantMenu mm)) {
                        throw new ToolError("No trading screen is open.");
                    }
                    int i = a.i("index");
                    if (i < 0 || i >= mm.getOffers().size()) throw Args.bad("index out of range (0.." + (mm.getOffers().size() - 1) + ")");
                    mm.setSelectionHint(i);
                    mm.tryMoveItems(i);
                    Game.player().connection.send(new ServerboundSelectTradePacket(i));
                    return J.obj().put("selected", i).put("result", Game.item(mm.getOffers().get(i).getResult())).build();
                }));

        t.addAction("goto", "Walk to a position with a simple pathfinder (walking, jumping up one block, dropping up to 3, swimming; it does "
                + "not dig, build, open doors or parkour). Returns the planned route at once; the walk runs in game, check it with "
                + "control_status. Moving yourself cancels it.",
                Schema.of().intReq("x", "X").intReq("y", "Y").intReq("z", "Z").num("tolerance", "How close counts as arrived (default 1)")
                        .bool("sprint", "Sprint on straight stretches (default true)"),
                a -> {
                    BlockPos goal = new BlockPos(a.i("x"), a.i("y"), a.i("z"));
                    double tol = Math.max(0, a.d("tolerance", 1));
                    boolean sprint = a.b("sprint", true);
                    Object[] from = Game.onClient(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        Game.player();
                        return new Object[]{mc.level, Nav.startFor(mc)};
                    });
                    ClientLevel level = (ClientLevel) from[0];
                    BlockPos start = (BlockPos) from[1];
                    // the search reads blocks like the scans do, here on the HTTP thread, so the game never stutters for it
                    long t0 = System.nanoTime();
                    Nav.Plan plan = Game.offThread(() -> Nav.plan(level, start, goal, tol, 120_000));
                    double ms = (System.nanoTime() - t0) / 1e6;
                    if (plan.nodes().size() < 2 && !plan.complete()) {
                        throw new ToolError("No route found from " + start.toShortString() + " (searched " + plan.expanded() + " spots).");
                    }
                    Game.onClient(() -> {
                        if (Minecraft.getInstance().level != level) throw new ToolError("The world changed while planning; try again.");
                        Control.run(new Nav.Walk(plan, goal, tol, sprint));
                        return null;
                    });
                    BlockPos end = plan.nodes().get(plan.nodes().size() - 1);
                    return J.obj().put("route_complete", plan.complete()).put("steps", plan.nodes().size() - 1).put("ends_at", Game.pos(end))
                            .put("searched", plan.expanded()).put("plan_ms", J.r(ms))
                            .put("note", plan.complete() ? "walking" : "no full route; walking to the closest reachable spot, then replanning").build();
                });
    }

    // ---------------------------------------------------------------- helpers

    static void setRot(LocalPlayer p, float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90.0F, 90.0F);
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double hz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, hz) * (180.0 / Math.PI));
        setRot(p, yaw, pitch);
    }

    private static JsonObject use(Args a) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = Game.player();
        Control.unpause(mc);
        if (a.has("entity")) lookAt(p, Game.entity(a.i("entity")).getBoundingBox().getCenter());
        else if (a.has("x")) lookAt(p, Vec3.atCenterOf(new BlockPos(a.i("x"), a.i("y"), a.i("z"))));
        ((MinecraftInvoker) mc).mcplink$pick(1.0F);
        JsonObject target = InfoTools.hit(mc.hitResult);
        int hold = Math.min(1200, a.i("hold_ticks", 0));
        if (hold <= 0) {
            ((MinecraftInvoker) mc).mcplink$startUseItem();
            return J.obj().put("clicked", target).build();
        }
        Control.run(new Control.Task() {
            int left = hold;
            boolean started;

            public String name() {
                return "holding right-click (" + left + " ticks)";
            }

            public String tick(Minecraft m) {
                if (!started) {
                    m.options.keyUse.setDown(true);
                    ((MinecraftInvoker) m).mcplink$startUseItem();
                    started = true;
                }
                m.options.keyUse.setDown(true);
                return left-- <= 0 ? "done" : null;
            }

            public void stop(Minecraft m) {
                m.options.keyUse.setDown(false);
                if (m.player != null && m.player.isUsingItem()) m.gameMode.releaseUsingItem(m.player);
            }
        });
        return J.obj().put("holding", hold).put("target", target).build();
    }

    private static JsonObject mine(Args a) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        BlockPos pos = new BlockPos(a.i("x"), a.i("y"), a.i("z"));
        if (level.getBlockState(pos).isAir()) throw new ToolError("There is no block at " + pos.toShortString() + ".");
        double reach = p.blockInteractionRange();
        if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > reach + 0.5) {
            throw new ToolError("Block is out of reach (" + J.r(p.getEyePosition().distanceTo(Vec3.atCenterOf(pos))) + " > " + J.r(reach) + ").");
        }
        Control.unpause(mc);
        BlockState wasState = level.getBlockState(pos);
        String was = Game.state(wasState);
        int timeout = Math.max(1, Math.min(600, a.i("timeout", 20))) * 20;
        Control.run(new Control.Task() {
            int ticks;
            boolean started;

            public String name() {
                return "mining " + pos.toShortString();
            }

            public String tick(Minecraft m) {
                // compare blocks, not air: ice and waterlogged blocks leave water behind, and some blocks change state when hit
                BlockState now = m.level.getBlockState(pos);
                if (now.getBlock() != wasState.getBlock()) {
                    return "broken " + Game.shortId(Game.id(wasState)) + (now.isAir() ? "" : ", now " + Game.shortId(Game.id(now)));
                }
                if (++ticks > timeout) return "timed out";
                lookAt(m.player, Vec3.atCenterOf(pos));
                ((MinecraftInvoker) m).mcplink$pick(1.0F);
                if (!(m.hitResult instanceof BlockHitResult bh) || !bh.getBlockPos().equals(pos)) {
                    return "blocked: crosshair is on " + InfoTools.hit(m.hitResult);
                }
                m.missTime = 0;
                if (!started) {
                    ((MinecraftInvoker) m).mcplink$startAttack();
                    started = true;
                }
                // the game's own tick keeps breaking it (see MinecraftAttackMixin)
                Control.holdAttack(true);
                return null;
            }

            public void stop(Minecraft m) {
                Control.holdAttack(false);
                if (m.gameMode != null) m.gameMode.stopDestroyBlock();
            }
        });
        return J.obj().put("mining", Game.pos(pos)).put("block", was).build();
    }

    private static JsonObject place(Args a) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        BlockPos pos = new BlockPos(a.i("x"), a.i("y"), a.i("z"));
        if (!level.getBlockState(pos).canBeReplaced()) throw new ToolError("Position " + pos.toShortString() + " is occupied by " + Game.state(level.getBlockState(pos)));
        Vec3 eye = p.getEyePosition();
        double reach = p.blockInteractionRange() + 0.5;
        // faces of solid neighbours that touch pos and point towards the eye, nearest first
        record Face(BlockPos against, Direction face, Vec3 point, double dist) {
        }
        java.util.List<Face> faces = new java.util.ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
            Vec3 point = Vec3.atCenterOf(n).add(normal.scale(0.5));
            if (eye.subtract(point).dot(normal) <= 0) continue;   // the face looks away from the player
            faces.add(new Face(n, face, point, eye.distanceTo(point)));
        }
        if (faces.isEmpty()) throw new ToolError("No solid neighbour with a face towards you to place against at " + pos.toShortString() + ".");
        faces.sort(java.util.Comparator.comparingDouble(Face::dist));
        if (faces.get(0).dist() > reach) throw new ToolError("Too far to place (" + J.r(faces.get(0).dist()) + " blocks).");
        Control.unpause(mc);
        float yaw = p.getYRot(), pitch = p.getXRot();
        String seen = null;
        for (Face f : faces) {
            if (f.dist() > reach) break;
            // the centre first, then points towards the face's edges and corners, for a face that is partly hidden
            for (Vec3 aim : aimPoints(f.point(), f.face())) {
                if (eye.distanceTo(aim) > reach) continue;
                lookAt(p, aim);
                ((MinecraftInvoker) mc).mcplink$pick(1.0F);
                // the ray must land on that very face: hitting another face of the same block would put the block somewhere else
                if (mc.hitResult instanceof BlockHitResult bh && bh.getBlockPos().equals(f.against()) && bh.getDirection() == f.face()) {
                    ((MinecraftInvoker) mc).mcplink$startUseItem();
                    return J.obj().put("placed_against", Game.pos(f.against())).put("face", f.face().getName())
                            .put("now", Game.state(level.getBlockState(pos))).build();
                }
                if (seen == null) seen = InfoTools.hit(mc.hitResult).toString();
            }
        }
        setRot(p, yaw, pitch);
        ((MinecraftInvoker) mc).mcplink$pick(1.0F);
        throw new ToolError("Can't see a face to place against (tried " + faces.size() + "; crosshair was on " + seen + ").");
    }

    private static final double[][] AIM = {{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}};

    /** Nine points on a block face: its centre, then towards each edge and corner. */
    private static java.util.List<Vec3> aimPoints(Vec3 centre, Direction face) {
        Vec3 u = face.getAxis() == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 v = face.getAxis() == Direction.Axis.Z ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        java.util.List<Vec3> out = new java.util.ArrayList<>(AIM.length);
        for (double[] o : AIM) out.add(centre.add(u.scale(o[0])).add(v.scale(o[1])));
        return out;
    }

    static boolean isEntityHit(HitResult h, Entity e) {
        return h instanceof EntityHitResult eh && eh.getEntity() == e;
    }
}
