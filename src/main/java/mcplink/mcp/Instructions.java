package mcplink.mcp;

/** Sent once in the initialize reply so the model knows how to use the tools well. */
final class Instructions {
    private Instructions() {
    }

    static final String TEXT = """
            MCP Link reads the player's own Minecraft client (26.3), so everything here is what that client legitimately knows:
            blocks and light in loaded chunks, entities the server is tracking for the player, chat, screens, inventory and timings.
            It cannot see server-only state: structure bounding boxes (fortress pieces etc.), biome spawn lists, mob AI memories
            (villager beds/job sites, unless sleeping), mob caps, persistence flags, or anything outside loaded chunks.

            Start with `status`. Coordinates are block coordinates; boxes are inclusive x1..x2 etc., or a radius around the player.
            For builds and farms: `blocks_map` (ASCII layers) and `blocks_count`/`blocks_find`. For spawning: `spawn_check`
            (+ `blocks_map mode=light`). For villagers and mob movement: `entities`, `track_start`/`track_get`, `mob_path`.
            For lag: `perf` and `chunks`. Keep boxes small enough to answer the question; big scans are capped.
            `packets` keeps the most recent packets of every type separately, so rare ones are still there later.

            Action tools (move, goto, mine, use, chat_send...) only work while the player has switched control on in game
            (default key Right Ctrl, Right Option on Mac; `control_status` names the key). Ask before doing anything with
            consequences: chat and commands are sent as the player. Multi-tick actions return at once; poll `control_status`
            for the outcome.

            For anything that has to react in game faster than a tool call (alerts when something appears, auto-reactions,
            packet filters or rewrites, HUD overlays, client-only visuals), write a JavaScript script: read `script_help` first,
            then `script_install` and `script_logs`; `script_eval` runs code once. Scripts only run while the player has
            "Allow Scripts" on in MCP Link's settings; they don't need control on.

            The game's own code is readable: `classes` finds classes of the game and its mods, `class_info` lists members with
            signatures, `source` decompiles a class. Names changed a lot in 26.x, so look them up rather than guessing. Its data
            and asset files too: `resources` searches them (loot tables, structure templates, worldgen, recipes, tags, models)
            and `resource_read` reads one (structure .nbt files as a summary). A server's own datapacks and server-only mods
            never reach the client, so for a server those files show what the installed mods ship, not necessarily what it uses.

            If `status` reports a newer mod version than the tools you see suggest (a tool named in these instructions is
            missing), the client cached an old tool list: ask the player to reconnect the MCP server in their client.
            """;
}
