package mcplink.tools;

import mcplink.McpLink;
import mcplink.Game;
import mcplink.J;
import mcplink.mcp.Args;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mcp.ToolRegistry.ToolError;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The game's data and asset files (loot tables, structure templates, worldgen, recipes, tags, models, blockstates, lang...)
 * from the game jar and every mod, and in singleplayer the effective versions with the world's datapacks and the player's
 * resource packs applied. On a server, its own datapacks and server-only mods can differ and aren't visible to the client.
 */
public final class ResourceTools {
    private ResourceTools() {
    }

    record Src(String origin, Path path) {
    }

    /** "data/minecraft/loot_table/chests/end_city_treasure.json" to every mod that ships it. */
    private static volatile Map<String, List<Src>> index;

    private static Map<String, List<Src>> index() {
        Map<String, List<Src>> m = index;
        if (m != null) return m;
        synchronized (ResourceTools.class) {
            if (index != null) return index;
            Map<String, List<Src>> out = new TreeMap<>();
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String id = mod.getMetadata().getId();
                for (Path root : mod.getRootPaths()) {
                    for (String top : new String[]{"data", "assets"}) {
                        Path dir = root.resolve(top);
                        if (!Files.isDirectory(dir)) continue;
                        try (Stream<Path> s = Files.walk(dir)) {
                            s.filter(Files::isRegularFile).forEach(p -> {
                                String rel = root.relativize(p).toString().replace('\\', '/');
                                out.computeIfAbsent(rel, k -> new ArrayList<>()).add(new Src(id, p));
                            });
                        } catch (Exception e) {
                            McpLink.LOG.debug("Could not index {}", dir, e);
                        }
                    }
                }
            }
            index = Collections.unmodifiableMap(out);
            return index;
        }
    }

    /** "data/ns/path" from a full path, or from "ns:path" plus a kind; adds .json or .nbt when the extension is left out. */
    private static String normalize(String path, String kind) {
        String p = path.trim().replace('\\', '/');
        if (p.startsWith("/")) p = p.substring(1);
        if (!p.startsWith("data/") && !p.startsWith("assets/")) {
            int c = p.indexOf(':');
            String ns = c < 0 ? "minecraft" : p.substring(0, c);
            String rest = c < 0 ? p : p.substring(c + 1);
            p = (kind.equals("assets") ? "assets/" : "data/") + ns + "/" + rest;
        }
        if (p.indexOf('.', p.lastIndexOf('/') + 1) < 0) {
            Map<String, List<Src>> idx = index();
            if (idx.containsKey(p + ".json")) return p + ".json";
            if (idx.containsKey(p + ".nbt")) return p + ".nbt";
        }
        return p;
    }

    private static Identifier identifier(String full) {
        String[] parts = full.split("/", 3);   // data|assets, namespace, path
        if (parts.length < 3) return null;
        return Identifier.tryParse(parts[1] + ":" + parts[2]);
    }

    /** The bytes to show and where they came from: the effective version when the game can tell, else the mods' copies. */
    private record Found(byte[] bytes, String from, List<String> providers, String note) {
    }

    private static Found load(String full) throws Exception {
        List<Src> srcs = index().getOrDefault(full, List.of());
        List<String> providers = new ArrayList<>();
        for (Src s : srcs) providers.add(s.origin());
        boolean data = full.startsWith("data/");
        Identifier id = identifier(full);
        ResourceManager rm = null;
        String rmName = null;
        if (id != null) {
            Minecraft mc = Minecraft.getInstance();
            if (!data) {
                rm = mc.getResourceManager();
                rmName = "the game's resources with your resource packs";
            } else {
                IntegratedServer sp = mc.getSingleplayerServer();
                if (sp != null) {
                    rm = sp.getResourceManager();
                    rmName = "this world's data with its datapacks";
                }
            }
        }
        if (rm != null) {
            List<Resource> stack = rm.getResourceStack(id);
            if (!stack.isEmpty()) {
                List<String> packs = new ArrayList<>();
                for (Resource r : stack) packs.add(r.sourcePackId());
                Resource top = rm.getResource(id).orElse(stack.get(stack.size() - 1));
                try (InputStream in = top.open()) {
                    return new Found(in.readAllBytes(), top.sourcePackId(), packs, "Effective version from " + rmName + ".");
                }
            }
        }
        if (srcs.isEmpty()) throw new ToolError("No file " + full + ". Search with resources.");
        // without the game's resource manager, a mod's copy normally wins over vanilla's
        Src pick = srcs.get(0);
        for (Src s : srcs) if (!s.origin().equals("minecraft")) pick = s;
        String note = data && Minecraft.getInstance().getSingleplayerServer() == null
                ? "From the installed mods' jars. On a server, its datapacks and server-only mods may change this file."
                : srcs.size() > 1 ? "Several mods ship this file; showing " + pick.origin() + "'s." : null;
        return new Found(Files.readAllBytes(pick.path()), pick.origin(), providers, note);
    }

    // ---------------------------------------------------------------- structure templates

    private static JsonElement nbtJson(byte[] bytes) throws Exception {
        CompoundTag tag;
        boolean gz = bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        if (gz) tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        else tag = NbtIo.read(new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(bytes))), NbtAccounter.unlimitedHeap());
        return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
    }

    private static String stateName(JsonObject e) {
        String name = e.has("Name") ? e.get("Name").getAsString() : "?";
        if (!e.has("Properties")) return name;
        List<String> props = new ArrayList<>();
        for (var p : e.getAsJsonObject("Properties").entrySet()) props.add(p.getKey() + "=" + p.getValue().getAsString());
        Collections.sort(props);
        return name + "[" + String.join(",", props) + "]";
    }

    /** Loot table ids anywhere in a block entity's data (chests' LootTable, a vault's config.loot_table...). */
    private static void lootTables(JsonElement e, List<String> out) {
        if (e.isJsonObject()) {
            for (var en : e.getAsJsonObject().entrySet()) {
                String k = en.getKey().toLowerCase(java.util.Locale.ROOT);
                if ((k.equals("loottable") || k.endsWith("loot_table")) && en.getValue().isJsonPrimitive()) out.add(en.getValue().getAsString());
                else lootTables(en.getValue(), out);
            }
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) lootTables(x, out);
        }
    }

    private static String shorten(JsonElement e, int max) {
        String s = e.toString();
        return s.length() > max ? s.substring(0, max) + "...(" + s.length() + " chars)" : s;
    }

    /** Size, block palette with counts, block entities (loot tables, jigsaw targets...) and entities of a structure template. */
    private static JsonObject structure(JsonObject t, Pattern match, int limit) {
        J j = J.obj();
        j.put("size", t.get("size"));
        JsonArray palette = t.has("palette") ? t.getAsJsonArray("palette")
                : t.has("palettes") && !t.getAsJsonArray("palettes").isEmpty() ? t.getAsJsonArray("palettes").get(0).getAsJsonArray() : new JsonArray();
        if (t.has("palettes")) j.put("palettes", t.getAsJsonArray("palettes").size());
        List<String> names = new ArrayList<>();
        for (JsonElement e : palette) names.add(stateName(e.getAsJsonObject()));
        int[] counts = new int[names.size()];
        JsonArray blockEntities = new JsonArray(), matched = new JsonArray();
        int beTotal = 0, matchTotal = 0;
        JsonArray blocks = t.has("blocks") ? t.getAsJsonArray("blocks") : new JsonArray();
        for (JsonElement be : blocks) {
            JsonObject b = be.getAsJsonObject();
            int st = b.get("state").getAsInt();
            if (st >= 0 && st < counts.length) counts[st]++;
            String name = st >= 0 && st < names.size() ? names.get(st) : "?";
            if (match != null && match.matcher(name).find() && matchTotal++ < limit) {
                matched.add(J.obj().put("pos", b.get("pos")).put("state", name).build());
            }
            if (b.has("nbt")) {
                if (match != null && !match.matcher(name).find()) continue;
                if (beTotal++ < limit) {
                    JsonObject nbt = b.getAsJsonObject("nbt");
                    J x = J.obj().put("pos", b.get("pos")).put("state", name);
                    for (String k : new String[]{"pool", "target", "name", "final_state", "joint"}) {
                        if (nbt.has(k)) x.put(k, nbt.get(k));
                    }
                    List<String> loot = new ArrayList<>();
                    lootTables(nbt, loot);
                    x.opt("loot_tables", loot);
                    blockEntities.add(x.put("nbt", shorten(nbt, 600)).build());
                }
            }
        }
        JsonObject pal = new JsonObject();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) order.add(i);
        order.sort((x, y) -> counts[y] - counts[x]);
        for (int i : order) {
            if (counts[i] == 0) continue;
            if (match != null && !match.matcher(names.get(i)).find()) continue;
            pal.addProperty(names.get(i), counts[i]);
        }
        j.put("blocks", blocks.size()).put("palette", pal);
        if (match != null) j.put("matching_blocks", matchTotal).put("positions", matched);
        j.put("block_entities", beTotal).put("block_entity_list", blockEntities);
        JsonArray ents = new JsonArray();
        int entTotal = 0;
        if (t.has("entities")) {
            for (JsonElement ee : t.getAsJsonArray("entities")) {
                JsonObject e = ee.getAsJsonObject();
                JsonObject nbt = e.has("nbt") ? e.getAsJsonObject("nbt") : new JsonObject();
                String id = nbt.has("id") ? nbt.get("id").getAsString() : "?";
                if (match != null && !match.matcher(id).find()) continue;
                if (entTotal++ < limit) ents.add(J.obj().put("pos", e.get("blockPos")).put("id", id).put("nbt", shorten(nbt, 300)).build());
            }
        }
        j.put("entities", entTotal).put("entity_list", ents);
        return j.build();
    }

    private static boolean texty(String path) {
        return path.matches(".*\\.(json|mcmeta|txt|lang|properties|fsh|vsh|glsl|snbt|mcfunction|csv|md|toml|yml|yaml)$");
    }

    // ---------------------------------------------------------------- tools

    public static void register(ToolRegistry t) {
        t.add("resources", "Search the data and asset files of the game and every installed mod by path (case-insensitive regex). "
                        + "Data: data/<ns>/loot_table, structure (templates .nbt), worldgen/structure, worldgen/template_pool, "
                        + "worldgen/biome, recipe, tags/..., advancement, enchantment... Assets: assets/<ns>/models, blockstates, "
                        + "textures, lang, shaders. Read one with resource_read.",
                Schema.of().strReq("query", "Regex on the path, e.g. 'loot_table/.*end_city', 'structure/end_city/.*vault'")
                        .str("kind", "data, assets or any (default any)").str("from", "Only files shipped by this mod id (minecraft, enderscape...)")
                        .integer("limit", "Max results (default 100)"),
                a -> Game.heavy(() -> {
                    Pattern q = Pattern.compile(a.s("query"), Pattern.CASE_INSENSITIVE);
                    String kind = a.s("kind", "any").toLowerCase(java.util.Locale.ROOT);
                    String from = a.s("from", null);
                    int limit = Math.max(1, Math.min(1000, a.i("limit", 100)));
                    JsonArray arr = new JsonArray();
                    int total = 0;
                    for (var e : index().entrySet()) {
                        String p = e.getKey();
                        if (kind.equals("data") && !p.startsWith("data/")) continue;
                        if (kind.equals("assets") && !p.startsWith("assets/")) continue;
                        if (from != null && e.getValue().stream().noneMatch(s -> s.origin().equalsIgnoreCase(from))) continue;
                        if (!q.matcher(p).find()) continue;
                        if (total++ >= limit) continue;
                        List<String> origins = new ArrayList<>();
                        for (Src s : e.getValue()) origins.add(s.origin());
                        arr.add(J.obj().put("path", p).put("from", origins).build());
                    }
                    return J.obj().put("total", total).put("files", arr).put("indexed", index().size()).build();
                }));

        t.add("resource_read", "Read a data or asset file of the game or a mod. JSON and text come back pretty-printed (with 'lines' "
                        + "and 'find' for long ones). Structure templates (.nbt) come back as a summary: size, block palette with "
                        + "counts, block entities with their loot tables and jigsaw targets, entities; 'match' lists the positions of "
                        + "blocks whose state matches a regex; full=true gives the whole NBT as JSON. Singleplayer shows the effective "
                        + "file (world datapacks, your resource packs); on a server, the server's own datapacks can differ.",
                Schema.of().strReq("path", "data/<ns>/... or assets/<ns>/..., or ns:path with 'kind' (extension optional)")
                        .str("kind", "data (default) or assets, when the path is ns:path").str("match", "Structures: regex on block states/entity ids")
                        .bool("full", "Structures/NBT: the whole file as JSON instead of a summary").integer("limit", "Structures: max list entries (default 200)")
                        .str("lines", "Text: line range, e.g. 1-200").str("find", "Text: regex, matching lines with context")
                        .integer("context", "Lines around 'find' hits (default 3)"),
                a -> Game.heavy(() -> read(a)));
    }

    private static JsonObject read(Args a) throws Exception {
        String full = normalize(a.s("path"), a.s("kind", "data").toLowerCase(java.util.Locale.ROOT));
        Found f = load(full);
        J j = J.obj().put("path", full).put("from", f.from()).opt("provided_by", f.providers().size() > 1 ? f.providers() : null)
                .put("bytes", f.bytes().length).opt("note", f.note());
        if (full.endsWith(".nbt") || full.endsWith(".dat")) {
            JsonElement json = nbtJson(f.bytes());
            boolean isStructure = json.isJsonObject() && json.getAsJsonObject().has("blocks")
                    && (json.getAsJsonObject().has("palette") || json.getAsJsonObject().has("palettes"));
            if (isStructure && !a.b("full", false)) {
                Pattern m = a.has("match") ? Pattern.compile(a.s("match"), Pattern.CASE_INSENSITIVE) : null;
                return j.put("structure", structure(json.getAsJsonObject(), m, Math.max(1, Math.min(2000, a.i("limit", 200))))).build();
            }
            String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json);
            return text(j, text, a);
        }
        if (full.endsWith(".png") && f.bytes().length > 24) {
            byte[] b = f.bytes();
            int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
            int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
            return j.put("image", w + "x" + h).build();
        }
        if (!texty(full)) return j.put("note", "Binary file; only its size is shown.").build();
        String text = new String(f.bytes(), StandardCharsets.UTF_8);
        if (full.endsWith(".json") || full.endsWith(".mcmeta")) {
            try {
                text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(JsonParser.parseString(text));
            } catch (Exception ignored) {
                // not strict JSON (comments...); show it as it is
            }
        }
        return text(j, text, a);
    }

    private static JsonObject text(J j, String text, Args a) {
        String[] lines = TextView.lines(text);
        j.put("total_lines", lines.length);
        J ranged = TextView.range(j, lines, a);
        if (ranged != null) return ranged.build();
        if (lines.length > 800) {
            return j.put("lines", "1-800").put("text", TextView.numbered(lines, 1, 800))
                    .put("more", "The file has " + lines.length + " lines; read on with 'lines' or search with 'find'.").build();
        }
        return j.put("text", TextView.numbered(lines, 1, lines.length)).build();
    }
}
