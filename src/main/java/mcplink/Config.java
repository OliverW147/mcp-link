package mcplink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config/mcp-link.json: where the MCP server listens, the bearer token MCP clients must send, and the in-game options
 * (also editable from Mod Menu).
 */
public final class Config {
    public String host = "127.0.0.1";
    public int port = 47812;
    public String token = "";
    /** Blue "MCP: reading" tag while tools are being called. */
    public boolean readingIndicator = true;
    /** Orange "MCP: control ON" tag while control is switched on. */
    public boolean controlIndicator = true;
    /** Action-bar messages when control is toggled or a task is stopped by your own movement. */
    public boolean controlMessages = true;
    /** While control is on, losing window focus does not open the pause menu (which would stop the agent's actions). */
    public boolean keepRunningWithControl = true;
    /** Lets the agent's in-game scripts run (they can act and change packets). Off until the player turns it on. */
    public boolean scriptsEnabled = false;
    /**
     * Scripts that were on stay on across launches. Off by default: every script starts switched off, so a script that
     * crashes the game can't do it again on the next launch.
     */
    public boolean autoStartScripts = false;
    /** Recent packets kept per packet type for the packets tool (0 keeps counts only). */
    public int packetsPerType = 100;

    public static final int MAX_PACKETS_PER_TYPE = 1000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static volatile Config current = new Config();
    private static Path file;
    /** Problems to show in game once the client is up (a broken file, a regenerated token). */
    static final List<String> problems = new ArrayList<>();

    public static Config get() {
        return current;
    }

    static Config load() {
        file = FabricLoader.getInstance().getConfigDir().resolve("mcp-link.json");
        Config cfg = null;
        String text = null;
        boolean broken = false;
        try {
            if (Files.exists(file)) {
                text = Files.readString(file);
                cfg = GSON.fromJson(text, Config.class);
                if (cfg == null) broken = true;   // empty file or a bare "null"
            }
        } catch (Exception e) {
            McpLink.LOG.warn("Could not read {}", file, e);
            broken = true;
        }
        if (broken) {
            cfg = new Config();
            // keep the token MCP clients were registered with, if it can still be found in the damaged file
            Matcher m = text == null ? null : TOKEN.matcher(text);
            if (m != null && m.find()) cfg.token = m.group(1);
            Path backup = file.resolveSibling("mcp-link.json.broken");
            try {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                problems.add("mcp-link.json could not be read; a copy was saved as mcp-link.json.broken and defaults are used.");
            } catch (Exception e) {
                McpLink.LOG.warn("Could not back up {}", file, e);
            }
        }
        if (cfg == null) cfg = new Config();
        if (cfg.token == null || cfg.token.isBlank()) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            cfg.token = HexFormat.of().formatHex(b);
            if (text != null) problems.add("A new token was generated; register it again in your MCP client (see the README).");
        }
        if (cfg.host == null || cfg.host.isBlank()) cfg.host = "127.0.0.1";
        if (cfg.port < 1 || cfg.port > 65535) {
            problems.add("Port " + cfg.port + " in mcp-link.json is not valid; using 47812.");
            cfg.port = 47812;
        }
        cfg.packetsPerType = Math.max(0, Math.min(MAX_PACKETS_PER_TYPE, cfg.packetsPerType));
        current = cfg;
        // rewrite only when something changed (new options, a fixed value), so a hand-edited file is left alone otherwise
        String json = GSON.toJson(cfg);
        if (text == null || broken || !json.equals(text.strip())) cfg.save();
        return cfg;
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("mcp-link.json.tmp");
            Files.writeString(tmp, GSON.toJson(this));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            McpLink.LOG.warn("Could not write {}", file, e);
        }
    }
}
