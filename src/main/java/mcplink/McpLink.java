package mcplink;

import mcplink.control.Control;
import mcplink.mcp.McpServer;
import mcplink.mcp.ToolRegistry;
import mcplink.script.ScriptTools;
import mcplink.script.Scripts;
import mcplink.state.ChatLog;
import mcplink.state.PacketLog;
import mcplink.state.Perf;
import mcplink.state.Tracker;
import mcplink.state.TradeLog;
import mcplink.tools.ActionTools;
import mcplink.tools.BlockTools;
import mcplink.tools.DataTools;
import mcplink.tools.EntityTools;
import mcplink.tools.InfoTools;
import mcplink.tools.SpawnTools;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class McpLink implements ClientModInitializer {
    public static final String ID = "mcplink";
    public static final String VERSION = FabricLoader.getInstance().getModContainer(ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    public static final Logger LOG = LoggerFactory.getLogger("MCP Link");
    /** Mac keyboards have no right Control key, so they get right Option instead. */
    static final boolean MAC = Util.getPlatform() == Util.OS.OSX;
    public static final String DEFAULT_KEY_NAME = MAC ? "Right Option" : "Right Ctrl";

    private static volatile long lastRequestNanos;
    private static volatile boolean lastRequestWasAction;
    private static KeyMapping controlKey;
    private static McpServer server;
    /** Shown as toasts once the game has finished loading. */
    private static final List<String> notices = new ArrayList<>();

    private static final SystemToast.SystemToastId NOTICE = new SystemToast.SystemToastId(15000L);

    @Override
    public void onInitializeClient() {
        Config cfg = Config.load();
        notices.addAll(Config.problems);
        PacketLog.setPerType(cfg.packetsPerType);

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
        controlKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mcplink.control",
                MAC ? InputConstants.KEY_RALT : InputConstants.KEY_RCONTROL, category));

        ToolRegistry tools = new ToolRegistry();
        InfoTools.register(tools);
        BlockTools.register(tools);
        SpawnTools.register(tools);
        EntityTools.register(tools);
        DataTools.register(tools);
        ActionTools.register(tools);
        ScriptTools.register(tools);
        mcplink.tools.CodeTools.register(tools);
        mcplink.tools.ResourceTools.register(tools);
        Scripts.load();

        ClientTickEvents.START_CLIENT_TICK.register(mc -> Perf.tickStart());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Perf.tickEnd();
            while (controlKey.consumeClick()) {
                Control.toggle();
            }
            Tracker.tick(mc);
            Control.tick(mc);
            Scripts.tick(mc);
            showNotices(mc);
        });
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> ChatLog.add(overlay ? "actionbar" : "system", msg, null));
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, type, ts) -> ChatLog.add("chat", msg, sender == null ? null : sender.name()));
        ClientSendMessageEvents.CHAT.register(text -> ChatLog.add("sent", Component.literal(text), null));
        ClientSendMessageEvents.COMMAND.register(cmd -> ChatLog.add("sent", Component.literal("/" + cmd), null));
        // script hooks (each is one volatile read when no script listens)
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> Scripts.entityAdded(entity));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> Scripts.entityRemoved(entity));
        ClientReceiveMessageEvents.ALLOW_GAME.register((msg, overlay) -> Scripts.chatIn(msg, overlay ? "actionbar" : "system"));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((msg, signed, sender, type, ts) -> Scripts.chatIn(msg, "chat"));
        ClientSendMessageEvents.ALLOW_CHAT.register(text -> Scripts.chatOut(text, false));
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> Scripts.chatOut(cmd, true));
        ClientSendMessageEvents.MODIFY_CHAT.register(Scripts::rewriteChatOut);
        ClientSendMessageEvents.MODIFY_COMMAND.register(Scripts::rewriteChatOut);
        // a new server or world starts clean: no control carried over, no trades or tracks from the last one
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            Control.switchOff(mc);
            TradeLog.clear();
            Tracker.clear();
            Scripts.joined();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> Control.switchOff(mc)));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "status"), Hud::render);

        server = new McpServer(tools, cfg.token);
        try {
            server.start(cfg.host, cfg.port);
            LOG.info("MCP server listening on http://{}:{}/mcp", cfg.host, cfg.port);
        } catch (Throwable e) {
            LOG.error("Could not start the MCP server on {}:{}", cfg.host, cfg.port, e);
            notices.add("The MCP server could not start on " + cfg.host + ":" + cfg.port + " (" + e.getMessage()
                    + "). Another game may be using the port; change it in config/mcp-link.json.");
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> server.stop());
    }

    private static void showNotices(Minecraft mc) {
        if (notices.isEmpty() || mc.gui == null || mc.gui.overlay() != null) return;
        for (String n : notices) {
            SystemToast toast = new SystemToast(NOTICE, Component.literal("MCP Link"), Component.literal(n));
            mc.gui.toastManager().addToast(toast);
        }
        notices.clear();
    }

    public static void noteRequest(boolean action) {
        lastRequestNanos = System.nanoTime();
        lastRequestWasAction = action;
    }

    public static long nanosSinceRequest() {
        return lastRequestNanos == 0 ? Long.MAX_VALUE : System.nanoTime() - lastRequestNanos;
    }

    public static boolean lastRequestWasAction() {
        return lastRequestWasAction;
    }

    public static String controlKeyName() {
        return controlKey == null ? DEFAULT_KEY_NAME : controlKey.getTranslatedKeyMessage().getString();
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
