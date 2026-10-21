package mcplink;

import mcplink.script.Scripts;
import mcplink.state.PacketLog;
import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** MCP Link's options (Mod Menu's config button). Every change is written to config/mcp-link.json when the screen closes. */
public final class ConfigScreen extends OptionsSubScreen {
    public ConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("mcplink.config.title"));
    }

    private static OptionInstance.TooltipSupplier<Boolean> tip(String key) {
        return OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip"));
    }

    @Override
    protected void addOptions() {
        Config c = Config.get();
        OptionInstance<Boolean> reading = OptionInstance.createBoolean("mcplink.config.reading_indicator",
                tip("mcplink.config.reading_indicator"), c.readingIndicator, v -> c.readingIndicator = v);
        OptionInstance<Boolean> control = OptionInstance.createBoolean("mcplink.config.control_indicator",
                tip("mcplink.config.control_indicator"), c.controlIndicator, v -> c.controlIndicator = v);
        OptionInstance<Boolean> messages = OptionInstance.createBoolean("mcplink.config.control_messages",
                tip("mcplink.config.control_messages"), c.controlMessages, v -> c.controlMessages = v);
        OptionInstance<Boolean> keepRunning = OptionInstance.createBoolean("mcplink.config.keep_running",
                tip("mcplink.config.keep_running"), c.keepRunningWithControl, v -> c.keepRunningWithControl = v);
        OptionInstance<Integer> packets = new OptionInstance<>("mcplink.config.packets_per_type",
                OptionInstance.cachedConstantTooltip(Component.translatable("mcplink.config.packets_per_type.tooltip")),
                (caption, value) -> value == 0
                        ? Options.genericValueLabel(caption, Component.translatable("mcplink.config.packets_per_type.counts_only"))
                        : Options.genericValueLabel(caption, value),
                new OptionInstance.IntRange(0, Config.MAX_PACKETS_PER_TYPE / 10).xmap(v -> v * 10, v -> v / 10, true),
                Codec.intRange(0, Config.MAX_PACKETS_PER_TYPE),
                c.packetsPerType - c.packetsPerType % 10,
                v -> {
                    c.packetsPerType = v;
                    PacketLog.setPerType(v);
                });
        OptionInstance<Boolean> scripts = OptionInstance.createBoolean("mcplink.config.scripts",
                tip("mcplink.config.scripts"), c.scriptsEnabled, v -> {
                    c.scriptsEnabled = v;
                    c.save();
                    Scripts.setAllowed(v);
                });
        OptionInstance<Boolean> autoStart = OptionInstance.createBoolean("mcplink.config.auto_start_scripts",
                tip("mcplink.config.auto_start_scripts"), c.autoStartScripts, v -> {
                    c.autoStartScripts = v;
                    c.save();
                });
        this.list.addSmall(reading, control, messages, keepRunning);
        this.list.addBig(packets);
        this.list.addBig(scripts);
        this.list.addBig(autoStart);
        this.list.addBig(Button.builder(Component.translatable("mcplink.config.scripts_list"),
                b -> this.minecraft.gui.setScreen(new ScriptsScreen(this))).width(310).build());
    }

    @Override
    public void removed() {
        Config.get().save();
    }
}
