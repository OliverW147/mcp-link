# MCP Link

MCP Link is a client-side Fabric mod for Minecraft 26.3. It lets any MCP-compatible AI agent see what your own game
client knows (your position, inventory, nearby blocks and mobs, chat, villager trades, lag stats) so you can ask it
questions while you play. It can also:

- **Read the game itself**: decompile any class of the game or a mod on demand, search the data and asset files of the
  game and every mod (loot tables, structure templates, recipes, worldgen, tags), and list every registry the client has.
- **Look deeper at what's going on**: inspect any live object in the client, see the packets the server sends, track mobs
  over time, ask a mob's own pathfinder where it can walk, and check where mobs can spawn.
- **Play for you**, with your permission: walk, pathfind, mine, place, use items, attack, move items around, trade with
  villagers and chat.
- **Run scripts in your game**: JavaScript it writes for anything that needs to react instantly, like alerts,
  auto-reactions, HUD overlays and packet filters.

It only runs on your computer. The server sees nothing extra.

The full tool list, every setting and notes for developers are in [REFERENCE.md](REFERENCE.md).

## What you need

- Minecraft **26.3** with **Fabric Loader 0.19.5 or newer**
- **Fabric API** for 26.3 (from Modrinth or CurseForge)
- An **MCP client** that supports the Streamable HTTP transport and custom headers (most do: Claude Code, Cursor,
  VS Code, Codex and others)

## Install

1. Use Fabric for 26.3.
2. Put the `mcp-link-*.jar` and the Fabric API jar into the instance's `mods` folder.
3. Start the game once and get to the main menu. This creates `config/mcp-link.json` next to the `mods` folder.
4. Open `config/mcp-link.json` and copy the `token` value. It looks like a long string of letters and numbers.

## Connect your MCP client

Add a server to your client with these settings, using your own token in place of `YOUR_TOKEN`:

- Name: `minecraft` (any name works)
- Transport: Streamable HTTP (sometimes just called "http")
- URL: `http://127.0.0.1:47812/mcp`
- Header: `Authorization: Bearer YOUR_TOKEN`

Clients configured with a JSON file usually take something like this (check your client's docs for the exact shape):

```json
{
  "mcpServers": {
    "minecraft": {
      "url": "http://127.0.0.1:47812/mcp",
      "headers": { "Authorization": "Bearer YOUR_TOKEN" }
    }
  }
}
```

Clients with a CLI have an add command instead. For example, in Claude Code:

```
claude mcp add --scope user --transport http minecraft http://127.0.0.1:47812/mcp --header "Authorization: Bearer YOUR_TOKEN"
```

A client that only runs local (stdio) servers can reach it through a bridge such as
`npx mcp-remote http://127.0.0.1:47812/mcp --header "Authorization: Bearer YOUR_TOKEN"`.

Then:

1. Start Minecraft first, then your client. If the client was already open, reconnect the `minecraft` server.
2. Ask away.

## Letting the agent control the game

Reading always works while the game is running. Actions (moving, mining, placing, using items, sending chat) only work
after you press **Right Ctrl** in game (**Right Option** on a Mac). You can rebind it under Controls, MCP Link,
"Toggle MCP control".

- An **orange** tag on screen means the agent has control. Press the key again to take it back.
- While control is on, the game keeps running when you click into another window (normally it would pause), so the agent can
  finish walking or mining while you type. Turn "Run Unfocused" off in the settings if you'd rather it paused.
- A **blue** tag means the agent is just reading.
- Pressing any movement key yourself stops whatever movement the agent started.
- Chat messages and commands are sent as you, so keep control off unless you mean it.

## Settings

With [Mod Menu](https://modrinth.com/mod/modmenu) installed, open Mods, MCP Link, and click the settings button to turn the
reading and control tags or the control messages off, change "Run Unfocused", or set how many packets of each type are kept.
Without Mod Menu, edit `config/mcp-link.json` while the game is closed.

**Scripts.** The agent can also install small scripts that run inside your game (for example an alert when a screaming goat
shows up). They only run after you turn on **Allow Scripts** in the settings, and they can do anything your game can,
including acting for you without control being on, so only turn it on if you want that. **Scripts...** in the settings lists
them with an on/off switch each. Every script is switched off again when the game starts, so one that crashes the game
can't keep crashing it; turn on **Auto-start Scripts** if you want scripts that were on to start with the game.

## Troubleshooting

- **No `minecraft` tools in your client**: the game wasn't running when the client started. Reconnect the server.
- **"Missing or wrong bearer token"**: the token in your client doesn't match the config. Fix the `Authorization` header
  in the client's server settings (with a CLI, remove the server and add it again with the right token).
- **Port already in use** (a toast says the server could not start): another game with MCP Link may be running, or
  Windows reserved the port. Change `port` in `config/mcp-link.json`, restart the game, and use the same port in your client's
  server URL.
