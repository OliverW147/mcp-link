# MCP Link reference

Client-side Fabric mod for Minecraft 26.3 that runs an MCP server on `127.0.0.1:47812/mcp`, so any MCP client can read what this
client knows and, while you allow it, act in game. Works on servers and in singleplayer. Nothing is sent to the server
except what your actions would send anyway.

## Setup

1. Build: `JAVA_HOME=<jdk-25> ./gradlew build`, copy `build/libs/mcp-link-*.jar` into the instance's `mods/` (needs Fabric API).
2. First launch writes `config/mcp-link.json` with a random bearer token (host, port and token can be edited there).
3. Register it once in your MCP client as a Streamable HTTP server at `http://127.0.0.1:47812/mcp` with the header
   `Authorization: Bearer <token>` (see [README.md](README.md) for examples).

## Control

Reading always works while the game runs. Actions (move, goto, mine, use, chat...) only run after you press the
"Toggle MCP control" key (default Right Ctrl, Right Option on Mac; rebindable in Controls). An orange HUD tag shows while
control is on; a blue one shows while the agent is reading. Pressing a movement key yourself stops any movement the agent started.
While control is on the game doesn't pause when its window loses focus, so the agent can keep walking and mining while you type.
Leaving or joining a world switches control off.

## Settings

Mod Menu's config button (or `config/mcp-link.json`, read at launch) has:

- `readingIndicator`, `controlIndicator`: the blue and orange HUD tags
- `controlMessages`: the action-bar messages when control is toggled or your movement stops the agent
- `keepRunningWithControl`: skip the pause-on-lost-focus menu while control is on (default on)
- `packetsPerType`: how many recent packets of each type the `packets` tool keeps (default 100, 0 = counts only)
- `scriptsEnabled`: "Allow Scripts", lets the agent's in-game scripts run (default off); the Scripts... button lists them
- `autoStartScripts`: "Auto-start Scripts", scripts that were on start again at launch (default off: every script is switched
  off at launch, so one that crashes the game can't crash it again)
- `host`, `port`, `token`: where the MCP server listens and the bearer token (file only)

A file that can't be read is kept as `mcp-link.json.broken` and its token is reused when it can still be found.

## Tools

- Overview: `status`, `player`, `inventory`, `screen` (containers, villager trades), `players`, `chat_log`, `world_info`,
  `scoreboard`
- Blocks: `block_info`, `blocks_count`, `blocks_find`, `blocks_map` (ASCII layers, light maps), `biomes`, `look_target`,
  `line_of_sight`
- Spawning: `spawn_check` (game placement + collision + light rules, distance bands)
- Entities: `entities`, `entity_info`, `track_start`/`track_get`/`track_stop`, `mob_path` (the mob's own pathfinder)
- Lag: `perf` (FPS, client tick, server TPS from time packets, entity/block-entity counts), `chunks`
- Everything else: `registries` / `registry` (every registry and tag the client has, incl. server/mod content like enchantments,
  with full definitions), `inspect` / `inspect_search` (reflection over any client object), `packets` (counts of every packet
  received, and the newest of each type kept for reading), `trades_seen` (all trade screens opened this session), `commands`
  (the server's command tree)
- Code: `classes` (search every class of the game, libraries and mods), `class_info` (members with signatures), `source`
  (a class decompiled on demand with Vineflower: outline, line ranges, regex search; the class as shipped, before mixins)
- Files: `resources` (search the data and asset files of the game and every mod), `resource_read` (JSON/text with line
  ranges and search; structure `.nbt` templates summarised with palette counts, block entities, loot tables and entities;
  singleplayer shows the effective file with the world's datapacks and your resource packs)
- Scripts: `script_help`, `script_install`, `script_list`, `script_source`, `script_toggle`, `script_remove`, `script_logs`,
  `script_eval`
- Actions: `chat_send`, `look`, `move`, `stop`, `hotbar`, `attack`, `use`, `mine`, `place`, `drop`, `swap_hands`,
  `open_inventory`, `close_screen`, `click_slot`, `select_trade`, `goto`, plus `control_status`

The client cannot know server-only state: structure bounding boxes, biome spawn lists, mob AI memories, mob caps, persistence.

## Notes for porting

`.mcsrc/` holds the unpacked Loom sources for reading game code (`./gradlew genSources`, then unzip the merged sources jar).
Mixin targets: `KeyboardInput.tick`, `ClientPacketListener.handleSetTime/handleLogin/handleMerchantOffers` and field
`serverChunkRadius`, `Connection.channelRead0` (the Packet overload) and field `packetListener`, `MultiPlayerGameMode.interact`,
`Minecraft.startAttack/continueAttack/startUseItem/pick/pauseIfInactive/runTick/shouldEntityAppearGlowing`,
`Connection.send(Packet, ChannelFutureListener, boolean)`, `Entity.getTeamColor`. In 26.3 the open screen lives in `Gui`
(`mc.gui.screen()`).

## Scripts

For things that must react inside the game faster than a tool call, the agent writes JavaScript (Rhino 1.9, bundled) with
`script_install`: alerts when something appears, automatic reactions, packet filters and rewrites, HUD overlays. Scripts get
game events (tick, entity added/removed, chat both ways, packets both ways with drop/replace, HUD drawing, screen changes), a
`game` helper object (notify, client-only chat, sounds, HUD lines, glowing outlines, attack/use/look, chat and commands, send
packets, reflection into anything) and full Java access to the game's classes. They are saved in
`config/mcp-link/scripts/` and start again at launch, but only while "Allow Scripts" and "Auto-start Scripts" are on (otherwise
every script is switched off at launch). Each call into a script has a time limit (50 ms, 2 s for loading), so a runaway loop
is cut off instead of freezing the game; a script that times out three times or fails fifty times in a row is stopped.
`script_help` explains it all to an agent starting from nothing (overview, API, examples, gotchas, how to look up names, what
can change on screen).
