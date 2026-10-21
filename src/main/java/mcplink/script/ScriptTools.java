package mcplink.script;

import mcplink.Game;
import mcplink.J;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mcp.ToolRegistry.ToolError;
import mcplink.tools.Reflect;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** MCP tools for in-game scripts. They need "Allow scripts" switched on in MCP Link's settings to run anything. */
public final class ScriptTools {
    private ScriptTools() {
    }

    private static final String OFF = "Scripts are switched off: the player has to turn on \"Allow scripts\" in MCP Link's settings "
            + "(Mod Menu, or scriptsEnabled in config/mcp-link.json).";

    static final String API = """
            Scripts are JavaScript (Rhino: let/const, arrow functions, template strings, spread, destructuring, Map/Set,
            for-of over Java collections; NOT class syntax, async/await or modules). Java strings, numbers (doubles) and
            booleans arrive as JavaScript values. Use `let` inside loops, blocks and functions: Rhino's `const` is one variable
            per script, so in a loop it keeps its first value. script_help has examples and more gotchas; classes, class_info
            and source find real 26.3 names.
            Globals: `game` (below), `mc` (the Minecraft instance), Packages.*, java.*, importClass/importPackage/JavaImporter.
            Java objects work directly: mc.player.getX(), e.isScreamingGoat(), mc.level.entitiesForRendering(); private fields via
            game.get(obj, "a.b") and private methods via game.call(obj, "name", args...).

            Events, game.on(event, fn) or game.on(event, regex, fn) (regex on the packet type or the message text):
              tick ()  |  entityAdded (entity)  |  entityRemoved (entity)  |  screen (screen or null, when it changes)
              chat (text, component, kind)       return false to hide the message
              chatSend (text, isCommand)         return false to cancel, or a string to send instead
              packetIn (packet, type)            NETWORK thread; return false to drop, or a packet to replace it
              packetOut (packet, type)           return false to cancel, or a packet to send instead
              hud (draw)                         every frame: draw.text(s,x,y,rgb), draw.fill(x1,y1,x2,y2,argb), draw.top
              stop ()                            when the script is stopped
            Packet type names look like ClientboundSetEntityMotionPacket or ClientboundMoveEntityPacket.Pos.
            Everything else runs on the game thread. Data sent with an entity's spawn (e.g. a goat's screaming flag) arrives just
            after entityAdded, so check it in game.after(1, ...).

            game: on/off(id), every(ticks, fn), after(ticks, fn), run(fn) (game thread), stop(), log(x), tick(), name(),
              controlOn(), player(), level(), mc()
              notify(title, text) toast with a ping | chat(text) client-only line | actionbar(text) | ping() |
              sound(id, volume, pitch) | hud(key, text[, rgb]) | hudFor(key, text, rgb, ticks) | hudClear(key) |
              glow(entity[, rgb]) outline through walls | unglow(entity)
              entities([typeGlob, radius]) nearest first | nearest(typeGlob, radius) | players() | entity(id) | id(entity) |
              dist(entity)
              lookAt(x, y, z) / lookAt(entity) | attack(entity) turn and left-click | click() | use() right-click |
              useOn(entity) | say(text) chat as the player | command(cmd) | send(packet)
              type(className) a Java class for statics/new | get(obj, path) | call(obj, method, args...)
            Each call into a script may take 50 ms (2 s for the top level); 3 timeouts or 50 errors in a row stop it.
            """;


    static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put("overview", """
                In-game scripts are JavaScript that runs inside the player's game client, written and installed by you
                (script_install). They react within the same tick, which tool calls can't: alerts when something appears,
                automatic reactions, packet filters and rewrites, HUD overlays, client-only visuals. They can do anything the
                game can, through the `game` helper object and direct access to every Java class of the game and its mods.

                Lifecycle: saved under config/mcp-link/scripts/ and started at install, but ONLY while the player has
                "Allow Scripts" on in MCP Link's settings (script_list shows scripts_allowed). Once allowed, scripts run
                whether or not control is on. At launch every script is switched off unless the player has "Auto-start
                Scripts" on (then scripts that were on start again); script_toggle switches one back on. The player can
                switch each script on or off in Mods > MCP Link > Scripts... Installing a script with the same name replaces it.

                Work loop: script_eval to try code once and inspect values, script_install, then script_logs for game.log output
                and errors (with line numbers). script_source shows an installed script. Topics: api, examples, gotchas, lookup,
                render.""");
        HELP.put("api", API);
        HELP.put("lookup", """
                Finding real 26.3 names. Many changed in 26.x, so don't trust memory of older versions:
                  classes        search every class of the game, its libraries and mods by regex ("Goat", "enderscape.*Vault")
                  class_info     a class's fields, constructors and methods with full signatures (private ones too)
                  source         decompiled source of a class, as an outline with line numbers, a line range, or regex hits
                  inspect        live values of any object (fields, getters) by path from mc, player, level...
                  script_eval    try calls for real and see what comes back
                Names to know: Identifier (was ResourceLocation), BuiltInRegistries.BLOCK/ITEM/ENTITY_TYPE, EntityTypes (the
                constants; EntityType is the class), mc.gui.screen() / mc.gui.setScreen(), mc.gui.hud.getChat(),
                GuiGraphicsExtractor (HUD drawing), ClientboundXxxPacket / ServerboundXxxPacket. Nested classes are written
                with $ in game.type: "net.minecraft.world.entity.Display$BlockDisplay". The source tool shows classes as shipped:
                mixins from mods (and MCP Link's own) change some of them at runtime.""");
        HELP.put("gotchas", """
                Rhino (the JavaScript engine):
                  - Use `let` inside loops, blocks and functions. `const` is one variable per script: in a loop it keeps its
                    first value, and declaring the same const name twice is an error. Top-level const is fine.
                  - No `class` syntax, async/await or modules. let, arrow functions, template strings, spread, destructuring,
                    Map/Set, for-of (also over Java lists and sets) all work.
                  - Strings, numbers and booleans from Java arrive as JavaScript values; numbers are always doubles. Pass whole
                    numbers to int parameters as usual. Other Java objects: compare with .equals(), or === for the same object.
                  - Java arrays have .length; Java lists .size() and .get(i).
                The game:
                  - packetIn handlers run on the NETWORK thread: don't touch the world there; use game.run(fn) to act.
                  - Every call into a script has 50 ms (2 s for the top level). Spread big work over ticks (a queue worked
                    through in "tick"), or check chunk section palettes first (section.maybeHas(javaPredicate)).
                  - Data that comes with an entity's spawn (a goat's screaming flag, names, equipment) arrives right after
                    entityAdded: check it in game.after(1 or 2, ...).
                  - Block entities (vaults, chests...) only exist on the client once something touches them: find blocks by
                    their states, not through chunk.getBlockEntities().
                  - Client-only entities you add (display entities for labels or outlines) need unique negative ids
                    (entity.setId(-2000000 - n)) and removing in game.on("stop", ...): mc.level.removeEntity(id,
                    Entity$RemovalReason.DISCARDED).
                  - Script variables reset when the script restarts (launch, reinstall, toggle).""");
        HELP.put("render", """
                What scripts can change on screen mid-game:
                  - Any texture loaded by id (mob skins, sky, GUI): register a DynamicTexture built from a NativeImage under that
                    id with mc.getTextureManager().register(id, texture). Block and item textures share an atlas: the clean way
                    is to write a resource pack into the game's resourcepacks folder and call mc.reloadResourcePacks().
                  - Client-only blocks: mc.level.setBlock(pos, state, 3) shows a block until the server sends the real one.
                  - Client-only display entities (Display$BlockDisplay, $ItemDisplay, $TextDisplay): floating blocks, items or
                    text labels; game.glow(entity, rgb) outlines one through walls (see examples: block outlines).
                  - Glow outlines on real entities, HUD text and drawing (game.hud, the "hud" event), video options via
                    mc.options, post-processing shaders through GameRenderer's spectator effect fields.
                What they can't: change the game's drawing code itself (hide one entity, tint one block, draw 3D lines). That
                needs a mixin, which only the mod can add when it is built.""");
        HELP.put("examples", """
                1. Alert and outline when a screaming goat loads in
                    const seen = new Set();
                    game.on("entityAdded", e => {
                      if (game.id(e) !== "minecraft:goat") return;
                      game.after(2, () => {   // the screaming flag arrives just after the spawn
                        if (!e.isScreamingGoat() || seen.has(e.getId())) return;
                        seen.add(e.getId());
                        game.glow(e, 0xFF5555);
                        game.notify("Screaming goat", Math.floor(e.getX()) + " " + Math.floor(e.getY()) + " " + Math.floor(e.getZ()));
                      });
                    });

                2. HUD line every frame
                    game.on("hud", draw => {
                      let p = mc.player;
                      if (!p) return;
                      draw.text(Math.floor(p.getX()) + " " + Math.floor(p.getY()) + " " + Math.floor(p.getZ()) + " facing " + p.getDirection(), 4, draw.top + 2, 0xFFFFFF);
                    });

                3. Packet filter: drop goat sounds (network thread, so no world access here)
                    game.on("packetIn", "ClientboundSound(Entity)?Packet", (p, type) => {
                      if (String(p.getSound().value().location()).includes("goat")) return false;   // false drops it
                    });

                4. Chat alias: "!home" runs /home instead of being sent
                    game.on("chatSend", (text, isCommand) => {
                      if (!isCommand && text === "!home") { game.command("home"); return false; }
                    });

                5. Automatic reaction: hit a named player whenever they're in reach (this really attacks, as the player)
                    const NAME = "SomeFriend";
                    game.every(10, () => {
                      for (let p of game.players()) {
                        if (p.getGameProfile().name() === NAME && game.dist(p) < 3) game.attack(p);
                      }
                    });

                6. Outline blocks through walls (only entities glow, so each block gets a client-only glowing block display
                   drawn just inside it; finding works by palette checks, then one section read per tick)
                    const Predicate = java.util.function.Predicate;
                    const BuiltIn = game.type("net.minecraft.core.registries.BuiltInRegistries");
                    const Identifier = game.type("net.minecraft.resources.Identifier");
                    const ChunkStatus = game.type("net.minecraft.world.level.chunk.status.ChunkStatus");
                    const EntityTypes = game.type("net.minecraft.world.entity.EntityTypes");
                    const BlockDisplay = game.type("net.minecraft.world.entity.Display$BlockDisplay");
                    const Matrix4f = game.type("org.joml.Matrix4f");
                    const Transformation = game.type("com.mojang.math.Transformation");
                    const RemovalReason = game.type("net.minecraft.world.entity.Entity$RemovalReason");
                    const COLOR = 0x55FF55, RADIUS = 8;   // chunks
                    const states = new java.util.HashSet(BuiltIn.BLOCK.getValue(Identifier.parse("minecraft:diamond_ore")).getStateDefinition().getPossibleStates());
                    let pred = null;
                    for (let st of states) pred = pred == null ? Predicate.isEqual(st) : pred.or(Predicate.isEqual(st));
                    const shown = new Map();
                    let chunks = [], sections = [], nextId = -2000000;
                    function outline(level, x, y, z, st) {
                      let k = x + "," + y + "," + z;
                      if (shown.has(k)) return;
                      let d = new BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
                      d.setId(nextId--);
                      d.setPos(x, y, z);
                      d.setBlockState(st);
                      d.setTransformation(new Transformation(new Matrix4f().translation(0.02, 0.02, 0.02).scale(0.96)));
                      d.setViewRange(4.0);
                      d.setGlowColorOverride(COLOR);
                      level.addEntity(d);
                      game.glow(d, COLOR);
                      shown.set(k, d);
                    }
                    game.every(100, () => {   // queue the chunks around the player every 5 s
                      let p = mc.player;
                      if (!p || chunks.length || sections.length) return;
                      for (let dx = -RADIUS; dx <= RADIUS; dx++) for (let dz = -RADIUS; dz <= RADIUS; dz++) chunks.push([(p.getBlockX() >> 4) + dx, (p.getBlockZ() >> 4) + dz]);
                    });
                    game.on("tick", () => {
                      let level = mc.level;
                      if (!level) return;
                      for (let n = 0; n < 40 && chunks.length; n++) {   // palette checks stay in Java
                        let c = chunks.pop();
                        let ch = level.getChunkSource().getChunk(c[0], c[1], ChunkStatus.FULL, false);
                        if (!ch) continue;
                        let secs = ch.getSections();
                        for (let i = 0; i < secs.length; i++) {
                          if (!secs[i].hasOnlyAir() && secs[i].maybeHas(pred)) sections.push([secs[i], c[0] << 4, level.getSectionYFromSectionIndex(i) << 4, c[1] << 4]);
                        }
                      }
                      if (!sections.length) return;
                      let s = sections.pop();
                      for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
                        let st = s[0].getBlockState(x, y, z);
                        if (states.contains(st)) outline(level, s[1] + x, s[2] + y, s[3] + z, st);
                      }
                    });
                    game.on("stop", () => {
                      for (let d of shown.values()) if (mc.level) mc.level.removeEntity(d.getId(), RemovalReason.DISCARDED);
                    });""");
    }

    public static void register(ToolRegistry t) {
        t.add("script_help", "How in-game scripts work, for writing one from scratch. Topics: overview, api, examples (alert, HUD, "
                        + "packet filter, chat alias, automatic reaction, block outlines), gotchas (Rhino and game pitfalls), lookup "
                        + "(finding 26.3 class and method names), render (what can be changed on screen). Read overview, gotchas "
                        + "and examples before the first script.",
                Schema.of().str("topic", "overview (default), api, examples, gotchas, lookup or render"),
                a -> {
                    String topic = a.s("topic", "overview").toLowerCase(java.util.Locale.ROOT);
                    String text = HELP.get(topic);
                    if (text == null) throw new ToolError("Topics: " + String.join(", ", HELP.keySet()));
                    return J.obj().put("topic", topic).put("text", text.strip()).put("topics", HELP.keySet()).build();
                });

        t.addWrite("script_install", "Install (or replace) a JavaScript script that runs inside the game, saved so it can start again at "
                        + "launch (only with the player's \"Auto-start scripts\" setting on): alerts, auto-reactions, packet filters, HUD overlays, anything the client can do. It starts at once "
                        + "when the player has \"Allow scripts\" on. Check script_logs after installing. New to this? Read script_help "
                        + "(overview, gotchas, examples) first.\n" + API,
                Schema.of().strReq("name", "Script name: a-z, 0-9, _ and -, up to 40 characters").strReq("code", "JavaScript source")
                        .str("description", "One line on what it does (shown in the in-game script list)")
                        .bool("enabled", "Start it (default true)"),
                a -> Game.onClient(() -> {
                    String err = Scripts.install(a.s("name"), a.s("code"), a.s("description", ""), a.b("enabled", true));
                    Script s = Scripts.get(a.s("name"));
                    J j = J.obj().put("saved", s.name).put("running", s.running).put("status", s.status).opt("error", err)
                            .put("handlers", handlers(s));
                    if (!Scripts.allowed()) j.put("note", OFF);
                    return j.build();
                }));

        t.add("script_list", "Installed in-game scripts: on/off, running or why not, what each listens for, and how much time it takes.",
                Schema.of(), a -> Game.onClient(() -> {
                    JsonArray arr = new JsonArray();
                    for (Script s : Scripts.sorted()) {
                        arr.add(J.obj().put("name", s.name).put("enabled", s.enabled).put("running", s.running).put("status", s.status)
                                .opt("description", s.description).put("handlers", handlers(s)).opt("timers", s.timers.size())
                                .put("calls", s.calls).put("avg_ms", s.calls == 0 ? 0 : J.r(s.nanos / 1e6 / s.calls))
                                .put("lines", s.source.lines().count()).build());
                    }
                    J j = J.obj().put("scripts_allowed", Scripts.allowed()).put("scripts", arr);
                    if (!Scripts.allowed()) j.put("note", OFF);
                    return j.build();
                }));

        t.add("script_source", "The source of an installed script.", Schema.of().strReq("name", "Script name"), a -> Game.onClient(() -> {
            Script s = Scripts.get(a.s("name"));
            if (s == null) throw new ToolError("No script " + a.s("name"));
            return new JsonPrimitive(s.source);
        }));

        t.addWrite("script_toggle", "Switch an installed script on or off (remembered across restarts only with the player's \"Auto-start scripts\" setting on; "
                        + "otherwise every script is off at launch).",
                Schema.of().strReq("name", "Script name").bool("enabled", "On (default true)"), a -> Game.onClient(() -> {
                    String err = Scripts.setEnabled(a.s("name"), a.b("enabled", true));
                    Script s = Scripts.get(a.s("name"));
                    if (s == null) throw new ToolError(err);
                    J j = J.obj().put("name", s.name).put("enabled", s.enabled).put("running", s.running).put("status", s.status).opt("error", err);
                    if (!Scripts.allowed()) j.put("note", OFF);
                    return j.build();
                }));

        t.addWrite("script_remove", "Stop and delete an installed script.", Schema.of().strReq("name", "Script name"),
                a -> Game.onClient(() -> J.obj().put("removed", Scripts.remove(a.s("name"))).build()));

        t.add("script_logs", "What scripts logged (game.log), their errors with script stack traces, and starts/stops, oldest first.",
                Schema.of().str("name", "Only this script").integer("after", "Only lines with seq greater than this")
                        .integer("limit", "Max lines (default 50)"),
                a -> {
                    JsonArray arr = new JsonArray();
                    java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
                    for (Scripts.LogLine l : Scripts.logs(a.s("name", null), a.has("after") ? (long) a.d("after") : 0, Math.max(1, Math.min(500, a.i("limit", 50))))) {
                        arr.add(J.obj().put("seq", l.seq()).put("time", fmt.format(java.time.Instant.ofEpochMilli(l.time()))).put("script", l.script())
                                .put("level", l.level()).put("text", l.text()).build());
                    }
                    return J.obj().put("last_seq", Scripts.lastLogSeq()).put("lines", arr).build();
                });

        t.addWrite("script_eval", "Run JavaScript once inside the game (game thread, up to 5 s) and get its last value back: quick "
                        + "questions or one-off actions without installing a script. Same globals as scripts, but no handlers or timers. "
                        + "JavaScript objects and arrays come back as JSON, Java objects as their fields.",
                Schema.of().strReq("code", "JavaScript; the value of the last expression is returned"),
                a -> {
                    if (!Scripts.allowed()) throw new ToolError(OFF);
                    Scripts.EvalResult r = Game.onClient(() -> Scripts.eval(a.s("code")));
                    JsonElement value;
                    if (r.json() != null) {
                        try {
                            value = JsonParser.parseString(r.json());
                        } catch (Exception e) {
                            value = new JsonPrimitive(r.json());
                        }
                    } else {
                        Object v = r.value();
                        value = v == null ? null : v instanceof String || v instanceof Number || v instanceof Boolean ? J.of(v)
                                : Game.safely(() -> Reflect.show(v, 1));
                    }
                    return J.obj().put("value", value).opt("logs", r.logs()).build();
                });
    }

    private static JsonElement handlers(Script s) {
        Map<String, Integer> m = new TreeMap<>();
        for (Script.Handler h : s.handlers) m.merge(h.event(), 1, Integer::sum);
        return new com.google.gson.Gson().toJsonTree(m);
    }
}
