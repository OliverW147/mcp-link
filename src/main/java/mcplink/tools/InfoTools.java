package mcplink.tools;

import mcplink.McpLink;
import mcplink.Game;
import mcplink.J;
import mcplink.control.Control;
import mcplink.mcp.Args;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mixin.ClientPacketListenerAccess;
import mcplink.state.ChatLog;
import mcplink.state.Perf;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Player, world, chat, screens, performance: the general "what is going on" tools. */
public final class InfoTools {
    /** Rays walk block by block on the client thread, so their length is capped. */
    static final int MAX_RAY = 1024;

    private InfoTools() {
    }

    public static void register(ToolRegistry t) {
        t.add("status", "One-call overview: connection (server address/brand or singleplayer world), dimension, player position/health, "
                + "control state, FPS, estimated server TPS, ping, view/simulation distance, loaded chunks and entities. Start here.",
                Schema.of(), a -> Game.onClient(InfoTools::status));

        t.add("player", "Detailed state of the local player: position, rotation, velocity, health, food, XP, effects, gamemode, abilities, "
                + "armor, held items, vehicle, what the crosshair targets, light and biome at the feet.",
                Schema.of(), a -> Game.onClient(InfoTools::player));

        t.add("inventory", "All items in the player's inventory (hotbar 0-8, main 9-35), armor, offhand and the selected slot. Components "
                + "(enchantments, names, damage) are included.", Schema.of(), a -> Game.onClient(InfoTools::inventory));

        t.add("screen", "What screen is open. For containers (chests, furnaces, hoppers...) lists every slot index with its item; for a villager "
                + "trading screen lists all trades with costs, uses and stock; for other screens lists button labels.",
                Schema.of(), a -> Game.onClient(InfoTools::screen));

        t.add("players", "Tab list (name, uuid, gamemode, ping, display name) plus the positions of player entities the client can see.",
                Schema.of(), a -> Game.onClient(InfoTools::players));

        t.add("chat_log", "Chat, system and (optionally) action-bar messages received since launch, oldest first. Use 'after' with the last "
                + "seq you saw to only get new lines.",
                Schema.of().integer("after", "Only lines with seq greater than this").integer("limit", "Max lines (default 50)")
                        .str("contains", "Case-insensitive text filter").bool("actionbar", "Include action-bar lines (default false)"),
                a -> chatLog(a));

        t.add("world_info", "Dimension and its rules (height, light rules for monster spawning), game/day time, weather, difficulty, world "
                + "border, world spawn, server view and simulation distance, client render distance. Singleplayer also gives the seed.",
                Schema.of(), a -> Game.onClient(InfoTools::worldInfo));

        t.add("perf", "Lag picture from the client: FPS (average, 1% low, worst frame), client tick time, server TPS estimated from time "
                + "packets (10 s and 60 s), seconds since the server last sent time, ping, memory, loaded chunks, entity and block-entity "
                + "counts by type, particles. Singleplayer adds the integrated server's MSPT.",
                Schema.of().integer("top", "How many entity / block-entity types to list (default 15)"), a -> Game.onClient(() -> perf(a)));

        t.add("chunks", "Per-chunk load around a point: entity count and types, block-entity count and types (hoppers, chests...). Sorted "
                + "by the heaviest chunks. Good for finding lag sources or where mobs pile up.",
                Schema.of().integer("radius", "Chunk radius around the centre (default 6, max 32)").integer("cx", "Centre block X (default player)")
                        .integer("cz", "Centre block Z (default player)").integer("limit", "Chunks to list (default 20)"),
                a -> Game.onClient(() -> chunks(a)));

        t.add("biomes", "Biome mix in an area (sampled on a grid, default every 4 blocks, which is the biome cell size), plus per-biome "
                + "bounding boxes. Use a thin box (y1=y2) to look at one layer.",
                Schema.of().area(64).integer("step", "Sample spacing in blocks (default 4)"), a -> Game.heavy(() -> biomes(a)));

        t.add("look_target", "What the crosshair is on (block with face and state, or entity), and the first block along the view ray "
                + "up to 'distance' blocks even beyond reach.",
                Schema.of().num("distance", "Ray length in blocks (default 128, max " + MAX_RAY + ")"), a -> Game.onClient(() -> lookTarget(a)));

        t.add("line_of_sight", "Whether a straight line between two points is blocked (by collision shapes, as mob vision uses), and the "
                + "first block hit. Points can be coordinates or entity ids (their eye position).",
                Schema.of().num("x1", "Start X").num("y1", "Start Y").num("z1", "Start Z").integer("from_entity", "Start at this entity's eyes")
                        .num("x2", "End X").num("y2", "End Y").num("z2", "End Z").integer("to_entity", "End at this entity's eyes")
                        .bool("visual", "Use visual shapes instead of collision (default false)"),
                a -> Game.onClient(() -> lineOfSight(a)));

        t.add("scoreboard", "Scoreboard objectives shown in the sidebar, tab list and below names, with the sidebar's scores.",
                Schema.of(), a -> Game.onClient(InfoTools::scoreboard));
    }

    // ---------------------------------------------------------------- status

    private static JsonObject status() {
        Minecraft mc = Minecraft.getInstance();
        J j = J.obj().put("mod", "mcp-link " + McpLink.VERSION);
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        j.put("in_world", level != null && p != null);
        j.put("control", Control.enabled() ? "on" : "off (player presses " + McpLink.controlKeyName() + ")");
        j.opt("task", Control.activity());
        j.opt("last_task", Control.lastOutcome());
        j.put("connection", connection(mc));
        Screen screen = mc.gui.screen();
        j.opt("screen", screen == null ? null : screen.getClass().getSimpleName());
        double[] f = Perf.frames(10);
        j.put("fps", J.r(Double.isNaN(f[0]) ? mc.getFps() : f[0]));
        j.put("tps_10s", Perf.tps(10));
        if (level != null && p != null) {
            j.put("dimension", level.dimension().identifier().toString());
            j.put("pos", Game.pos(p.position()));
            j.put("block", Game.pos(p.blockPosition()));
            j.put("health", J.r(p.getHealth()) + "/" + J.r(p.getMaxHealth()));
            j.put("food", p.getFoodData().getFoodLevel());
            j.put("gamemode", mc.gameMode == null ? null : mc.gameMode.getPlayerMode().getName());
            PlayerInfo self = p.connection.getPlayerInfo(p.getUUID());
            j.opt("ping_ms", self == null ? null : self.getLatency());
            j.put("server_view_distance", ((ClientPacketListenerAccess) p.connection).mcplink$serverChunkRadius());
            j.put("simulation_distance", level.getServerSimulationDistance());
            j.put("render_distance", mc.options.getEffectiveRenderDistance());
            j.put("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
            j.put("entities", level.getEntityCount());
            j.put("day_time", level.getDefaultClockTime() % 24000);
        }
        return j.build();
    }

    private static JsonObject connection(Minecraft mc) {
        J j = J.obj();
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            j.put("type", sp.isPublished() ? "singleplayer (open to LAN)" : "singleplayer");
            j.put("world", sp.getWorldData().getLevelName());
        } else {
            ServerData sd = mc.getCurrentServer();
            j.put("type", mc.getConnection() == null ? "none" : "multiplayer");
            if (sd != null) {
                j.put("address", sd.ip);
                j.put("name", sd.name);
            }
        }
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) j.opt("brand", conn.serverBrand());
        return j.build();
    }

    // ---------------------------------------------------------------- player

    private static JsonObject player() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = Game.player();
        ClientLevel level = Game.level();
        BlockPos feet = p.blockPosition();
        J j = J.obj()
                .put("name", p.getGameProfile().name())
                .put("uuid", p.getUUID().toString())
                .put("entity_id", p.getId())
                .put("dimension", level.dimension().identifier().toString())
                .put("pos", Game.pos(p.position()))
                .put("block", Game.pos(feet))
                .put("chunk", J.arr(feet.getX() >> 4, feet.getZ() >> 4))
                .put("yaw", J.r(p.getYRot()))
                .put("pitch", J.r(p.getXRot()))
                .put("facing", p.getDirection().getName())
                .put("velocity", Game.pos(p.getDeltaMovement()))
                .put("on_ground", p.onGround())
                .put("health", J.r(p.getHealth()))
                .put("max_health", J.r(p.getMaxHealth()))
                .opt("absorption", p.getAbsorptionAmount() > 0 ? J.r(p.getAbsorptionAmount()) : null)
                .put("food", p.getFoodData().getFoodLevel())
                .put("saturation", J.r(p.getFoodData().getSaturationLevel()))
                .put("xp_level", p.experienceLevel)
                .put("xp_progress", J.r(p.experienceProgress))
                .put("armor", p.getArmorValue())
                .put("air", p.getAirSupply() + "/" + p.getMaxAirSupply())
                .opt("on_fire", p.isOnFire())
                .put("gamemode", mc.gameMode == null ? null : mc.gameMode.getPlayerMode().getName())
                .put("flying", p.getAbilities().flying)
                .opt("sneaking", p.isShiftKeyDown())
                .opt("sprinting", p.isSprinting())
                .opt("elytra_flying", p.isFallFlying())
                .opt("sleeping", p.isSleeping())
                .put("selected_slot", p.getInventory().getSelectedSlot())
                .put("main_hand", Game.item(p.getMainHandItem()))
                .put("off_hand", Game.item(p.getOffhandItem()));
        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            effects.add(J.obj().put("effect", e.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("?"))
                    .put("amplifier", e.getAmplifier()).put("seconds", e.isInfiniteDuration() ? -1 : e.getDuration() / 20).build());
        }
        j.opt("effects", effects);
        JsonObject armor = new JsonObject();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            var it = Game.item(p.getItemBySlot(s));
            if (it != null) armor.add(s.getName(), it);
        }
        j.opt("armor_items", armor.isEmpty() ? null : armor);
        Entity v = p.getVehicle();
        if (v != null) j.put("vehicle", J.obj().put("id", v.getId()).put("type", Game.id(v)));
        j.put("light_at_feet", J.obj().put("block", level.getBrightness(LightLayer.BLOCK, feet)).put("sky", level.getBrightness(LightLayer.SKY, feet)));
        j.put("biome", level.getBiome(feet).unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
        j.put("crosshair", hit(mc.hitResult));
        return j.build();
    }

    static JsonObject hit(HitResult h) {
        if (h == null || h.getType() == HitResult.Type.MISS) return J.obj().put("type", "miss").build();
        if (h instanceof BlockHitResult b) {
            ClientLevel level = Game.level();
            return J.obj().put("type", "block").put("pos", Game.pos(b.getBlockPos())).put("face", b.getDirection().getName())
                    .put("state", Game.state(level.getBlockState(b.getBlockPos())))
                    .put("distance", J.r(Math.sqrt(Game.player().getEyePosition().distanceToSqr(b.getLocation())))).build();
        }
        if (h instanceof EntityHitResult e) {
            Entity en = e.getEntity();
            return J.obj().put("type", "entity").put("id", en.getId()).put("entity", Game.id(en)).put("pos", Game.pos(en.position()))
                    .opt("name", en.hasCustomName() ? en.getCustomName().getString() : null).build();
        }
        return J.obj().put("type", h.getType().name().toLowerCase(java.util.Locale.ROOT)).build();
    }

    // ---------------------------------------------------------------- inventory and screens

    private static JsonObject inventory() {
        LocalPlayer p = Game.player();
        var inv = p.getInventory();
        JsonArray slots = new JsonArray();
        var items = inv.getNonEquipmentItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            if (st.isEmpty()) continue;
            JsonObject o = Game.item(st).getAsJsonObject();
            o.addProperty("slot", i);
            slots.add(o);
        }
        J j = J.obj().put("selected_slot", inv.getSelectedSlot()).put("items", slots);
        JsonObject eq = new JsonObject();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            var it = Game.item(p.getItemBySlot(s));
            if (it != null) eq.add(s.getName(), it);
        }
        j.put("equipment", eq);
        Map<String, Integer> totals = new TreeMap<>();
        for (ItemStack st : items) {
            if (!st.isEmpty()) totals.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
        }
        j.put("totals", J.of(new com.google.gson.Gson().toJsonTree(totals)));
        j.put("free_slots", (int) items.stream().filter(ItemStack::isEmpty).count());
        return j.build();
    }

    private static JsonObject screen() {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.gui.screen();
        if (s == null) return J.obj().put("screen", null).put("note", "No screen open (playing).").build();
        J j = J.obj().put("screen", s.getClass().getSimpleName()).put("title", s.getTitle().getString());
        if (s instanceof AbstractContainerScreen<?> cs) {
            AbstractContainerMenu menu = cs.getMenu();
            j.put("menu", menu.getClass().getSimpleName()).put("container_id", menu.containerId);
            JsonArray slots = new JsonArray();
            int playerSlots = 0;
            for (Slot sl : menu.slots) {
                boolean playerInv = sl.container instanceof net.minecraft.world.entity.player.Inventory;
                if (playerInv) playerSlots++;
                ItemStack st = sl.getItem();
                if (st.isEmpty()) continue;
                JsonObject o = Game.item(st).getAsJsonObject();
                o.addProperty("slot", sl.index);
                if (playerInv) o.addProperty("player_inv", true);
                slots.add(o);
            }
            j.put("slot_count", menu.slots.size()).put("player_slots", playerSlots).put("items", slots);
            j.opt("carried", Game.item(menu.getCarried()));
            if (menu instanceof MerchantMenu mm) {
                JsonArray offers = new JsonArray();
                int idx = 0;
                for (MerchantOffer o : mm.getOffers()) {
                    offers.add(J.obj().put("index", idx++).put("cost_a", Game.item(o.getCostA())).put("cost_b", Game.item(o.getCostB()))
                            .put("result", Game.item(o.getResult())).put("uses", o.getUses() + "/" + o.getMaxUses())
                            .opt("out_of_stock", o.isOutOfStock()).put("xp", o.getXp()).build());
                }
                j.put("trader_level", mm.getTraderLevel()).put("trader_xp", mm.getTraderXp()).put("offers", offers);
            }
        } else {
            List<String> buttons = new ArrayList<>();
            for (var c : s.children()) {
                if (c instanceof AbstractWidget w) buttons.add(w.getMessage().getString());
            }
            j.opt("widgets", buttons);
        }
        return j.build();
    }

    // ---------------------------------------------------------------- players and chat

    private static JsonObject players() {
        LocalPlayer me = Game.player();
        JsonArray tab = new JsonArray();
        for (PlayerInfo pi : me.connection.getListedOnlinePlayers()) {
            tab.add(J.obj().put("name", pi.getProfile().name()).put("uuid", pi.getProfile().id().toString())
                    .put("gamemode", pi.getGameMode() == null ? null : pi.getGameMode().getName()).put("ping_ms", pi.getLatency())
                    .opt("display_name", pi.getTabListDisplayName() == null ? null : pi.getTabListDisplayName().getString()).build());
        }
        JsonArray seen = new JsonArray();
        for (Player pl : Game.level().players()) {
            if (pl == me) continue;
            seen.add(J.obj().put("name", pl.getGameProfile().name()).put("id", pl.getId()).put("pos", Game.pos(pl.position()))
                    .put("distance", J.r(pl.distanceTo(me))).put("health", J.r(pl.getHealth()))
                    .put("main_hand", Game.item(pl.getMainHandItem())).opt("sneaking", pl.isShiftKeyDown()).build());
        }
        return J.obj().put("tab_list", tab).put("visible_players", seen).build();
    }

    private static JsonObject chatLog(Args a) {
        long after = a.has("after") ? (long) a.d("after") : 0;
        var lines = ChatLog.since(after, Math.min(500, a.i("limit", 50)), a.s("contains", null), a.b("actionbar", false));
        JsonArray arr = new JsonArray();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
        for (var l : lines) {
            arr.add(J.obj().put("seq", l.seq()).put("time", fmt.format(java.time.Instant.ofEpochMilli(l.time()))).put("kind", l.kind())
                    .opt("sender", l.sender()).put("text", l.text()).build());
        }
        return J.obj().put("last_seq", ChatLog.lastSeq()).put("lines", arr).build();
    }

    // ---------------------------------------------------------------- world

    private static JsonObject worldInfo() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = Game.level();
        LocalPlayer p = Game.player();
        DimensionType dt = level.dimensionType();
        J j = J.obj().put("dimension", level.dimension().identifier().toString());
        j.put("dimension_type", J.obj().put("min_y", dt.minY()).put("height", dt.height()).put("logical_height", dt.logicalHeight())
                .put("has_skylight", dt.hasSkyLight()).put("has_ceiling", dt.hasCeiling()).put("coordinate_scale", dt.coordinateScale())
                .put("monster_spawn_block_light_limit", dt.monsterSpawnBlockLightLimit())
                .put("monster_spawn_light_level", dt.monsterSpawnLightTest().minInclusive() + ".." + dt.monsterSpawnLightTest().maxInclusive()));
        long day = level.getDefaultClockTime();
        j.put("game_time", level.getGameTime()).put("day_time", day % 24000).put("day", day / 24000);
        j.put("raining", level.isRaining()).put("thundering", level.isThundering());
        j.put("difficulty", level.getDifficulty().getSerializedName());
        var wb = level.getWorldBorder();
        j.put("world_border", J.obj().put("center", J.arr(J.r(wb.getCenterX()), J.r(wb.getCenterZ()))).put("size", J.r(wb.getSize())));
        j.put("world_spawn", Game.pos(level.getRespawnData().pos()));
        j.put("server_view_distance", ((ClientPacketListenerAccess) p.connection).mcplink$serverChunkRadius());
        j.put("simulation_distance", level.getServerSimulationDistance());
        j.put("render_distance", mc.options.getEffectiveRenderDistance());
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            j.put("seed", sp.overworld().getSeed());
            j.put("singleplayer_world", sp.getWorldData().getLevelName());
        }
        return j.build();
    }

    // ---------------------------------------------------------------- perf

    private static JsonObject perf(Args a) {
        Minecraft mc = Minecraft.getInstance();
        int top = a.i("top", 15);
        double[] f10 = Perf.frames(10), f60 = Perf.frames(60);
        double[] t = Perf.ticks(200);
        J j = J.obj();
        j.put("fps", J.obj().put("now", mc.getFps()).put("avg_10s", f10[0]).put("low1pct_10s", f10[1]).put("worst_frame_ms_10s", f10[2])
                .put("median_frame_ms_10s", f10[3]).put("avg_60s", f60[0]).put("low1pct_60s", f60[1]).put("worst_frame_ms_60s", f60[2]));
        j.put("client_tick_ms", J.obj().put("avg", t[0]).put("max", t[1]));
        j.put("server_tps", J.obj().put("10s", Perf.tps(10)).put("60s", Perf.tps(60)).put("300s", Perf.tps(300))
                .put("seconds_since_time_packet", Perf.secondsSinceServerTime())
                .put("note", "Estimated from time packets; values just under 20 are normal jitter."));
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            j.put("integrated_server_mspt", J.r(sp.getAverageTickTimeNanos() / 1e6));
        }
        Runtime rt = Runtime.getRuntime();
        j.put("memory_mb", J.obj().put("used", (rt.totalMemory() - rt.freeMemory()) >> 20).put("max", rt.maxMemory() >> 20));
        ClientLevel level = mc.level;
        if (level != null && mc.player != null) {
            PlayerInfo self = mc.player.connection.getPlayerInfo(mc.player.getUUID());
            j.opt("ping_ms", self == null ? null : self.getLatency());
            j.put("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
            Map<String, Integer> ents = new HashMap<>();
            int total = 0;
            for (Entity e : level.entitiesForRendering()) {
                ents.merge(Game.shortId(Game.id(e)), 1, Integer::sum);
                total++;
            }
            j.put("entities_total", total).put("entities_by_type", topMap(ents, top));
            Map<String, Integer> bes = new HashMap<>();
            int beTotal = 0;
            for (LevelChunk c : loadedChunks(level, mc.player.blockPosition(), ((ClientPacketListenerAccess) mc.player.connection).mcplink$serverChunkRadius() + 1)) {
                for (BlockEntity be : c.getBlockEntities().values()) {
                    bes.merge(Game.shortId(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString()), 1, Integer::sum);
                    beTotal++;
                }
            }
            j.put("block_entities_total", beTotal).put("block_entities_by_type", topMap(bes, top));
            j.put("particles", mc.particleEngine.countParticles());
        }
        return j.build();
    }

    static JsonObject topMap(Map<String, Integer> m, int top) {
        JsonObject o = new JsonObject();
        m.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(top).forEach(e -> o.addProperty(e.getKey(), e.getValue()));
        return o;
    }

    /** Loaded chunks within `radius` chunks of a block position (client thread). */
    static List<LevelChunk> loadedChunks(ClientLevel level, BlockPos centre, int radius) {
        List<LevelChunk> out = new ArrayList<>();
        int cx = centre.getX() >> 4, cz = centre.getZ() >> 4;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                LevelChunk c = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (c != null) out.add(c);
            }
        }
        return out;
    }

    private static JsonObject chunks(Args a) {
        ClientLevel level = Game.level();
        BlockPos pp = Game.player().blockPosition();
        BlockPos centre = new BlockPos(a.i("cx", pp.getX()), 0, a.i("cz", pp.getZ()));
        int radius = Math.max(0, Math.min(32, a.i("radius", 6)));
        record Row(int x, int z, Map<String, Integer> ents, int entN, Map<String, Integer> bes, int beN) {
        }
        Map<Long, Row> rows = new LinkedHashMap<>();
        for (LevelChunk c : loadedChunks(level, centre, radius)) {
            Map<String, Integer> bes = new HashMap<>();
            for (BlockEntity be : c.getBlockEntities().values()) {
                bes.merge(Game.shortId(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString()), 1, Integer::sum);
            }
            rows.put(c.getPos().pack(), new Row(c.getPos().x(), c.getPos().z(), new HashMap<>(), 0, bes, c.getBlockEntities().size()));
        }
        Map<Long, int[]> entCounts = new HashMap<>();
        for (Entity e : level.entitiesForRendering()) {
            long key = e.chunkPosition().pack();
            Row r = rows.get(key);
            if (r == null) continue;
            r.ents().merge(Game.shortId(Game.id(e)), 1, Integer::sum);
            entCounts.computeIfAbsent(key, k -> new int[1])[0]++;
        }
        List<Row> list = new ArrayList<>();
        for (var e : rows.entrySet()) {
            Row r = e.getValue();
            int n = entCounts.getOrDefault(e.getKey(), new int[1])[0];
            list.add(new Row(r.x(), r.z(), r.ents(), n, r.bes(), r.beN()));
        }
        list.sort(Comparator.comparingInt((Row r) -> r.entN() + r.beN()).reversed());
        JsonArray arr = new JsonArray();
        for (Row r : list.subList(0, Math.min(list.size(), a.i("limit", 20)))) {
            if (r.entN() + r.beN() == 0) break;
            arr.add(J.obj().put("chunk", J.arr(r.x(), r.z())).put("blocks", J.arr(r.x() * 16, r.z() * 16, r.x() * 16 + 15, r.z() * 16 + 15))
                    .put("entities", r.entN()).opt("entity_types", r.entN() == 0 ? null : topMap(r.ents(), 8))
                    .put("block_entities", r.beN()).opt("block_entity_types", r.beN() == 0 ? null : topMap(r.bes(), 8)).build());
        }
        return J.obj().put("chunks_loaded_in_radius", rows.size()).put("heaviest", arr).build();
    }

    private static JsonObject biomes(Args a) throws Exception {
        Game.Box b = Game.onClient(() -> Game.area(a, 64));
        int step = Math.max(1, a.i("step", 4));
        long samples = (long) ((b.x2() - b.x1()) / step + 1) * ((b.y2() - b.y1()) / step + 1) * ((b.z2() - b.z1()) / step + 1);
        if (samples > 400_000) throw new ToolRegistry.ToolError("Too many samples (" + samples + "); raise 'step' or shrink the box.");
        Scan.Snapshot snap = Scan.snapshot(b, Long.MAX_VALUE);
        Map<String, int[]> found = new HashMap<>();   // count, minx, miny, minz, maxx, maxy, maxz
        int total = 0;
        for (int x = b.x1(); x <= b.x2(); x += step) {
            for (int z = b.z1(); z <= b.z2(); z += step) {
                LevelChunk c = snap.chunks.get(Scan.chunkKey(x >> 4, z >> 4));
                if (c == null) continue;
                for (int y = b.y1(); y <= b.y2(); y += step) {
                    String id = c.getNoiseBiome(x >> 2, y >> 2, z >> 2).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                    int[] r = found.computeIfAbsent(id, k -> new int[]{0, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE});
                    r[0]++;
                    r[1] = Math.min(r[1], x);
                    r[2] = Math.min(r[2], y);
                    r[3] = Math.min(r[3], z);
                    r[4] = Math.max(r[4], x);
                    r[5] = Math.max(r[5], y);
                    r[6] = Math.max(r[6], z);
                    total++;
                }
            }
        }
        JsonArray arr = new JsonArray();
        final int tot = Math.max(1, total);
        found.entrySet().stream().sorted((x, y) -> y.getValue()[0] - x.getValue()[0]).forEach(e -> {
            int[] r = e.getValue();
            arr.add(J.obj().put("biome", e.getKey()).put("share", J.r(100.0 * r[0] / tot) + "%").put("box", J.arr(r[1], r[2], r[3], r[4], r[5], r[6])).build());
        });
        return J.obj().put("box", b.json()).put("samples", total).put("biomes", arr).build();
    }

    private static JsonObject lookTarget(Args a) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = Game.player();
        double dist = Math.max(0, Math.min(MAX_RAY, a.d("distance", 128)));
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1.0F).scale(dist));
        BlockHitResult far = Game.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
        return J.obj().put("crosshair", hit(mc.hitResult)).put("ray", hit(far)).build();
    }

    private static Vec3 point(Args a, String prefix, String entityKey) {
        if (a.has(entityKey)) return Game.entity(a.i(entityKey)).getEyePosition();
        return new Vec3(a.d("x" + prefix), a.d("y" + prefix), a.d("z" + prefix));
    }

    private static JsonObject lineOfSight(Args a) {
        Vec3 from = point(a, "1", "from_entity"), to = point(a, "2", "to_entity");
        if (from.distanceTo(to) > MAX_RAY) throw new ToolRegistry.ToolError("The line is " + J.r(from.distanceTo(to)) + " blocks long; max " + MAX_RAY + ".");
        ClipContext.Block shape = a.b("visual", false) ? ClipContext.Block.VISUAL : ClipContext.Block.COLLIDER;
        BlockHitResult r = Game.level().clip(new ClipContext(from, to, shape, ClipContext.Fluid.NONE, Game.player()));
        boolean clear = r.getType() == HitResult.Type.MISS;
        J j = J.obj().put("from", Game.pos(from)).put("to", Game.pos(to)).put("clear", clear);
        if (!clear) j.put("blocked_by", hit(r));
        return j.build();
    }

    private static JsonObject scoreboard() {
        Scoreboard sb = Game.level().getScoreboard();
        J j = J.obj();
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective o = sb.getDisplayObjective(slot);
            if (o == null) continue;
            J oj = J.obj().put("objective", o.getName()).put("title", o.getDisplayName().getString());
            if (slot == DisplaySlot.SIDEBAR) {
                JsonObject scores = new JsonObject();
                for (PlayerScoreEntry e : sb.listPlayerScores(o)) {
                    scores.addProperty(e.ownerName().getString(), e.value());
                }
                oj.put("scores", scores);
            }
            j.put(slot.getSerializedName(), oj);
        }
        return j.build();
    }
}
