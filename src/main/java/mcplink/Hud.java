package mcplink;

import mcplink.control.Control;
import mcplink.script.Scripts;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Top-left corner: a tag while the agent is reading, and always while control is on (each can be turned off), then any lines and
 * drawing from in-game scripts.
 */
final class Hud {
    private Hud() {
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden()) return;
        Config cfg = Config.get();
        String text = null;
        int color = 0;
        if (Control.enabled()) {
            if (cfg.controlIndicator) {
                String doing = Control.activity();
                text = "MCP: control ON" + (doing == null ? "" : " (" + doing + ")");
                color = 0xFFFFA040;
            }
        } else if (cfg.readingIndicator && McpLink.nanosSinceRequest() < 3_000_000_000L) {
            text = "MCP: reading";
            color = 0xFFA0C8FF;
        }
        int y = 2;
        if (text != null) {
            int w = mc.font.width(text);
            g.fill(2, 2, 6 + w, 14, 0x90000000);
            g.text(mc.font, text, 4, 4, color, false);
            y = 14;
        }
        Scripts.renderHud(g, y);
    }
}
