package mcplink.tools;

import mcplink.Game;
import mcplink.J;
import mcplink.mcp.Args;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mcp.ToolRegistry.ToolError;
import mcplink.state.PacketLog;
import mcplink.state.TradeLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The general-purpose tools: every registry and tag the client has (including server/datapack/mod content such as
 * enchantments), any object in the client via reflection, every packet received, trades seen and the command tree.
 */
public final class DataTools {
    private DataTools() {
    }

    public static void register(ToolRegistry t) {
        t.add("registries", "Every registry the client has, with entry and tag counts: built-in ones (blocks, items, entity types, "
                + "effects, attributes...) and the ones the server sent at join (enchantments, biomes, damage types, trims, banner "
                + "patterns, jukebox songs, painting/wolf/cat variants, dimension types, chat types, dialogs...). Mod and datapack "
                + "content shows up under its own namespace. Use 'registry' to read one.",
                Schema.of().str("match", "Only registries whose id contains this"), a -> Game.safely(() -> registries(a)));

        t.add("registry", "Entries of one registry (e.g. enchantment, worldgen/biome, item, block, damage_type, mob_effect, "
                + "trim_material, villager_profession...). With detail=true each entry's full definition as the game encodes it (for "
                + "enchantments: max level, supported/primary items, weight, costs, exclusive set, slots, effects). Filters by namespace "
                + "or id glob. Also lists the registry's tags, or the members of one tag.",
                Schema.of().strReq("id", "Registry id, e.g. enchantment or minecraft:worldgen/biome").str("match", "Entry id glob, e.g. enderscape:* or *sharp*")
                        .str("namespace", "Only entries from this namespace").bool("detail", "Full definitions (default false)")
                        .bool("tags", "List the registry's tags with sizes").str("tag", "List the members of this tag, e.g. minecraft:in_enchanting_table")
                        .integer("limit", "Max entries (default 200, 30 with detail)").integer("offset", "Skip this many entries"),
                a -> Game.safely(() -> registry(a)));

        t.add("inspect", "Read ANY object the client holds, by path, via reflection: all fields (private too) and side-effect-free getters, "
                + "with collections, maps and records expanded. Roots: " + Reflect.ROOTS + ". Path syntax: dots for fields/getters "
                + "(player.inventory), [n] for list/array/map index (level.players[0]), {key} for a map key, a registry on 'registries' or an entry in a registry "
                + "(registries{enchantment}{minecraft:sharpness}). Raise depth to expand nested objects; output is capped, so a view "
                + "that would be too big comes back at a lower depth. Use inspect_search to discover where something lives.",
                Schema.of().str("path", "e.g. player, mc.options, connection.advancements, gui.hud.bossOverlay, static:net.minecraft.client.KeyMapping")
                        .integer("depth", "How many levels to expand (default 1, max 6)").integer("limit", "Max items per collection/map (default 50)")
                        .integer("offset", "Skip this many items of the top collection").str("fields", "Regex: only top-level fields/getters whose name matches")
                        .bool("getters", "Also call the object's read-only getters (default false)").bool("statics", "Include static fields of the top object"),
                a -> {
                    try {
                        return Game.offThread(() -> inspect(a, true));
                    } catch (Exception e) {
                        if (!Game.concurrent(e)) throw e;
                        return Game.onClient(() -> inspect(a, false));
                    }
                });

        t.add("inspect_search", "Search the client's object graph below a path for fields whose name or class matches a regex (and "
                + "optionally values), returning paths to feed into inspect. E.g. pattern 'boss' under 'gui', 'advancement' under "
                + "'connection', 'seed|border' under 'level'.",
                Schema.of().str("path", "Root path (default mc)").strReq("pattern", "Regex for field names / class names (/ values)")
                        .integer("depth", "How deep to walk (default 4, max 8)").bool("values", "Also match simple values (strings, numbers, ids)")
                        .integer("limit", "Max hits (default 80)"),
                a -> Game.heavy(() -> {
                    try {
                        return Game.offThread(() -> search(a, 40_000));
                    } catch (Exception e) {
                        if (!Game.concurrent(e)) throw e;
                        return Game.onClient(() -> search(a, 8_000));
                    }
                }));

        t.add("packets", "Packets the server has sent this client since connecting. By default (no type/after) a summary per type: "
                + "how many arrived, how many are stored, when the last one came. Otherwise the stored packets themselves with all "
                + "their fields. The newest packets of EACH type are kept separately (setting 'packets per type', default 100), so "
                + "rare ones (titles, boss bars, merchant offers, sounds, block changes, entity spawns...) are still there after "
                + "floods of common ones. Bundles are unpacked, so entity spawn data shows up. Chunk and light data keep 8 each.",
                Schema.of().str("type", "Regex on packet class, e.g. Sound|Particle, BlockUpdate, AddEntity, MerchantOffers")
                        .integer("after", "Only packets with seq greater than this").integer("limit", "Max packets overall (default 30, max 500)")
                        .integer("per_type", "Max packets of each type (default: all stored)")
                        .integer("depth", "Field expansion depth (default 2, max 4)").bool("summary", "Counts per type instead of packets (default true when no type/after)"),
                a -> packets(a));

        t.add("trades_seen", "Every villager or wandering trader whose trading screen has been opened this session: who it is (id, "
                + "uuid, profession, level, xp, position) and all its offers exactly as the server sent them. Open each trader's screen "
                + "once in game (or with control on, 'use' on it) and they all collect here.",
                Schema.of().str("profession", "Only this profession, e.g. librarian").str("item", "Only traders offering an item whose id contains this"),
                a -> Game.onClient(() -> trades(a)));

        t.add("commands", "The command tree the server sent (what this player may run), with usage for a command.",
                Schema.of().str("command", "Show usage for this command (e.g. 'tp'); omit to list all").integer("limit", "Max usage lines (default 60)"),
                a -> Game.onClient(() -> commands(a)));
    }

    // ---------------------------------------------------------------- registries

    private static RegistryAccess access() {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        if (c == null) throw new ToolError("Not connected to a world.");
        return c.registryAccess();
    }

    private static final Map<ResourceKey<?>, Codec<?>> CODECS = new HashMap<>();

    private static synchronized Codec<?> codec(ResourceKey<?> key) {
        if (CODECS.isEmpty()) {
            for (var list : List.of(RegistryDataLoader.SYNCHRONIZED_REGISTRIES, RegistryDataLoader.WORLD_REGISTRIES,
                    RegistryDataLoader.DIMENSION_REGISTRIES, RegistryDataLoader.RELOADABLE_REGISTRIES)) {
                for (RegistryDataLoader.RegistryData<?> d : list) CODECS.putIfAbsent(d.key(), d.elementCodec());
            }
        }
        return CODECS.get(key);
    }

    private static Registry<?> findRegistry(String id) {
        RegistryAccess ra = access();
        Identifier ident = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (ident == null) throw Args.bad("Bad registry id " + id);
        Registry<?> r = ra.lookup(ResourceKey.createRegistryKey(ident)).orElse(null);
        if (r != null) return r;
        List<String> close = ra.registries().map(e -> e.key().identifier().toString()).filter(s -> s.contains(id)).limit(10).toList();
        throw new ToolError("No registry " + ident + (close.isEmpty() ? "" : ". Did you mean: " + String.join(", ", close)));
    }

    private static JsonObject registries(Args a) {
        String match = a.s("match", null);
        JsonArray arr = new JsonArray();
        access().registries().sorted((x, y) -> x.key().identifier().toString().compareTo(y.key().identifier().toString())).forEach(e -> {
            String id = e.key().identifier().toString();
            if (match != null && !id.contains(match)) return;
            Registry<?> r = e.value();
            Map<String, Integer> ns = new HashMap<>();
            for (Identifier k : r.keySet()) ns.merge(k.getNamespace(), 1, Integer::sum);
            J j = J.obj().put("id", id).put("entries", r.size()).put("tags", r.getTags().count());
            if (ns.size() > 1 || !ns.containsKey("minecraft")) j.put("namespaces", InfoTools.topMap(ns, 20));
            if (codec(e.key()) != null) j.put("definitions", true);
            arr.add(j.build());
        });
        return J.obj().put("count", arr.size()).put("registries", arr).build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonElement encode(Registry<?> r, Object value, DynamicOps<JsonElement> ops) {
        Codec codec = codec(r.key());
        if (codec != null) {
            var res = codec.encodeStart(ops, value);
            Object ok = res.result().orElse(null);
            if (ok instanceof JsonElement je) return je;
            return new JsonPrimitive("(encode failed: " + res.error().map(Object::toString).orElse("?") + ")");
        }
        if (value instanceof Item item) {
            JsonObject o = new JsonObject();
            o.add("components", DataComponentMap.CODEC.encodeStart(ops, item.components()).result().orElse(new JsonPrimitive("?")));
            return o;
        }
        Reflect.Opts opts = new Reflect.Opts();
        opts.limit = 20;
        return Reflect.render(value, 2, opts);
    }

    private static JsonObject registry(Args a) {
        Registry<?> r = findRegistry(a.s("id"));
        RegistryAccess ra = access();
        DynamicOps<JsonElement> ops = ra.createSerializationContext(JsonOps.INSTANCE);
        J j = J.obj().put("registry", r.key().identifier().toString()).put("size", r.size());
        if (a.has("tag")) {
            String tagId = a.s("tag").replace("#", "");
            Identifier tid = Identifier.tryParse(tagId.contains(":") ? tagId : "minecraft:" + tagId);
            HolderSet.Named<?> set = r.getTags().filter(n -> n.key().location().equals(tid)).findFirst()
                    .orElseThrow(() -> new ToolError("No tag #" + tid + " in " + r.key().identifier()));
            List<String> members = new ArrayList<>();
            for (Holder<?> h : set) members.add(Reflect.label(h));
            return j.put("tag", tid.toString()).put("members", members).build();
        }
        if (a.b("tags", false)) {
            JsonObject tags = new JsonObject();
            r.getTags().sorted((x, y) -> x.key().location().toString().compareTo(y.key().location().toString()))
                    .forEach(n -> tags.addProperty(n.key().location().toString(), n.size()));
            j.put("tags", tags);
        }
        boolean detail = a.b("detail", false);
        Pattern glob = a.has("match") ? Pattern.compile(Pattern.quote(a.s("match")).replace("*", "\\E.*\\Q")) : null;
        String ns = a.s("namespace", null);
        int limit = a.i("limit", detail ? 30 : 200), offset = a.i("offset", 0);
        List<Identifier> ids = r.keySet().stream().filter(id -> ns == null || id.getNamespace().equals(ns))
                .filter(id -> glob == null || glob.matcher(id.toString()).matches() || glob.matcher(id.getPath()).matches())
                .sorted((x, y) -> x.toString().compareTo(y.toString())).toList();
        j.put("matched", ids.size());
        if (detail) {
            JsonObject entries = new JsonObject();
            for (Identifier id : ids.stream().skip(offset).limit(limit).toList()) {
                Object v = r.getValue(id);
                try {
                    entries.add(id.toString(), encode(r, v, ops));
                } catch (Exception e) {
                    entries.addProperty(id.toString(), "(error: " + e + ")");
                }
            }
            j.put("entries", entries);
        } else {
            j.put("ids", ids.stream().skip(offset).limit(limit).map(Identifier::toString).toList());
        }
        if (offset + limit < ids.size()) j.put("next_offset", offset + limit);
        return j.build();
    }

    // ---------------------------------------------------------------- reflection

    private static JsonElement inspect(Args a, boolean allowShallower) {
        String path = a.s("path", "mc");
        Object o = Reflect.resolve(path);
        Reflect.Opts opts = new Reflect.Opts();
        opts.limit = a.i("limit", 50);
        opts.offset = a.i("offset", 0);
        opts.getters = a.b("getters", false);
        opts.statics = a.b("statics", false);
        if (a.has("fields")) opts.fieldFilter = Pattern.compile(a.s("fields"), Pattern.CASE_INSENSITIVE);
        int depth = Math.max(0, Math.min(6, a.i("depth", 1)));
        JsonElement out = Reflect.render(o, depth, opts);
        // a complete shallower view is more useful than a deep one cut off halfway
        while (allowShallower && opts.truncated && depth > 1) {
            depth--;
            out = Reflect.render(o, depth, opts);
        }
        JsonObject r = new JsonObject();
        r.addProperty("path", path);
        r.addProperty("depth_used", depth);
        if (opts.truncated) r.addProperty("truncated", "output limit reached; narrow with 'fields', 'limit', 'offset' or a longer path");
        r.add("value", out);
        String s = r.toString();
        if (s.length() > 150_000) {
            return new JsonPrimitive(s.substring(0, 150_000) + "...(truncated; narrow with 'fields', 'limit' or a longer path)");
        }
        return r;
    }

    private static JsonObject search(Args a, int maxVisits) {
        String path = a.s("path", "mc");
        Object o = Reflect.resolve(path);
        Pattern p = Pattern.compile(a.s("pattern"), Pattern.CASE_INSENSITIVE);
        var hits = Reflect.search(o, path, p, Math.min(8, a.i("depth", 4)), a.b("values", false), a.i("limit", 80), maxVisits);
        JsonArray arr = new JsonArray();
        for (var h : hits) arr.add(J.obj().put("path", h.path()).put("type", h.type()).opt("value", h.value()).build());
        return J.obj().put("hits", arr.size()).put("results", arr).build();
    }

    // ---------------------------------------------------------------- packets

    /** Runs on the HTTP thread only: the packet log is written from the network thread and never needs the game thread. */
    private static JsonObject packets(Args a) {
        boolean summary = a.b("summary", !a.has("type") && !a.has("after"));
        long now = System.currentTimeMillis();
        if (summary) {
            JsonArray arr = new JsonArray();
            PacketLog.stats().stream().sorted((x, y) -> Long.compare(y.count(), x.count())).forEach(st ->
                    arr.add(J.obj().put("type", st.type()).put("count", st.count()).put("stored", st.stored())
                            .put("last_s_ago", J.r((now - st.lastTime()) / 1000.0)).build()));
            return J.obj().put("since_s", (now - PacketLog.since()) / 1000).put("last_seq", PacketLog.lastSeq())
                    .put("stored_per_type", PacketLog.perType()).put("types", arr).build();
        }
        Pattern type = a.has("type") ? Pattern.compile(a.s("type"), Pattern.CASE_INSENSITIVE) : null;
        long after = a.has("after") ? (long) a.d("after") : 0;
        int limit = Math.max(1, Math.min(500, a.i("limit", 30)));
        int perType = a.has("per_type") ? Math.max(1, a.i("per_type")) : Integer.MAX_VALUE;
        Reflect.Opts opts = new Reflect.Opts();
        opts.limit = 30;
        opts.maxString = 600;
        opts.maxNodes = 3_000;
        opts.maxChars = 40_000;
        int depth = Math.max(0, Math.min(4, a.i("depth", 2)));
        List<PacketLog.Entry> entries = PacketLog.recent(after, type, limit, perType);
        List<JsonObject> out = new ArrayList<>();
        long size = 0;
        boolean cut = false;
        // newest first, so a reply that hits the size cap keeps the latest packets
        for (int i = entries.size() - 1; i >= 0; i--) {
            PacketLog.Entry e = entries.get(i);
            J j = J.obj().put("seq", e.seq()).put("s_ago", J.r((now - e.time()) / 1000.0)).put("type", e.type());
            JsonElement body;
            try {
                body = Game.offThread(() -> Reflect.render(e.packet(), depth, opts));
            } catch (Exception ex) {
                body = new JsonPrimitive("(unreadable: " + ex + ")");
            }
            if (body.isJsonObject()) {
                body.getAsJsonObject().remove("_class");
                body.getAsJsonObject().remove("_toString");
            }
            JsonObject o = j.put("data", body).build();
            size += o.toString().length();
            if (size > 200_000 && !out.isEmpty()) {
                cut = true;
                break;
            }
            out.add(o);
        }
        java.util.Collections.reverse(out);
        JsonArray arr = new JsonArray();
        out.forEach(arr::add);
        J r = J.obj().put("last_seq", PacketLog.lastSeq()).put("matched", entries.size()).put("packets", arr);
        if (cut) r.put("note", "Reply size cap reached: only the newest " + out.size() + " are shown; use 'after', 'per_type' or a narrower 'type'.");
        return r.build();
    }

    // ---------------------------------------------------------------- trades

    /** Called from the merchant-offers packet handler on the client thread. */
    public static void tradesReceived(ClientboundMerchantOffersPacket packet) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            int id = TradeLog.lastInteractId();
            Entity e = id < 0 ? null : mc.level.getEntity(id);
            J j = J.obj().put("seen", java.time.LocalTime.now().withNano(0).toString());
            if (e != null) {
                j.put("id", e.getId()).put("uuid", e.getUUID().toString()).put("type", Game.shortId(Game.id(e))).put("pos", Game.pos(e.position()));
                if (e.hasCustomName()) j.put("name", e.getCustomName().getString());
                if (e instanceof VillagerDataHolder v) {
                    VillagerData d = v.getVillagerData();
                    j.put("profession", d.profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"))
                            .put("biome_type", d.type().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
                }
            } else {
                j.put("note", "trader entity unknown (screen opened without a recorded right-click)");
            }
            j.put("level", packet.getVillagerLevel()).put("xp", packet.getVillagerXp());
            JsonArray offers = new JsonArray();
            int i = 0;
            for (MerchantOffer o : packet.getOffers()) {
                offers.add(J.obj().put("index", i++).put("cost_a", Game.item(o.getCostA())).put("cost_b", Game.item(o.getCostB()))
                        .put("result", Game.item(o.getResult())).put("uses", o.getUses() + "/" + o.getMaxUses())
                        .opt("out_of_stock", o.isOutOfStock()).opt("special_price_diff", o.getSpecialPriceDiff() == 0 ? null : o.getSpecialPriceDiff())
                        .opt("demand", o.getDemand() == 0 ? null : o.getDemand()).put("xp", o.getXp()).build());
            }
            j.put("offers", offers);
            TradeLog.put(e != null ? e.getUUID().toString() : "container-" + packet.getContainerId() + "-" + System.nanoTime(), j.build());
        } catch (Exception ex) {
            mcplink.McpLink.LOG.warn("Could not record trades", ex);
        }
    }

    private static JsonObject trades(Args a) {
        String prof = a.s("profession", null), item = a.s("item", null);
        JsonArray arr = new JsonArray();
        for (JsonObject o : TradeLog.all()) {
            if (prof != null && (!o.has("profession") || !o.get("profession").getAsString().contains(prof))) continue;
            if (item != null && !o.get("offers").toString().contains(item)) continue;
            arr.add(o);
        }
        return J.obj().put("traders", arr.size()).put("seen", arr)
                .put("note", "Only trades already unlocked are sent; higher levels are rolled by the server when the villager levels up.").build();
    }

    // ---------------------------------------------------------------- commands

    private static JsonObject commands(Args a) {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        if (c == null) throw new ToolError("Not connected.");
        CommandDispatcher<ClientSuggestionProvider> d = c.getCommands();
        ClientSuggestionProvider src = c.getSuggestionsProvider();
        if (!a.has("command")) {
            List<String> names = new ArrayList<>();
            for (CommandNode<ClientSuggestionProvider> n : d.getRoot().getChildren()) names.add(n.getName());
            names.sort(String::compareTo);
            return J.obj().put("count", names.size()).put("commands", names).build();
        }
        String name = a.s("command").replace("/", "");
        CommandNode<ClientSuggestionProvider> node = d.getRoot().getChild(name);
        if (node == null) throw new ToolError("The server did not send a /" + name + " command to this player.");
        String[] usage = d.getAllUsage(node, src, true);
        int limit = a.i("limit", 60);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, usage.length); i++) lines.add("/" + name + " " + usage[i]);
        return J.obj().put("command", name).put("usage", lines).put("total", usage.length).build();
    }
}
