package mcplink;

import mcplink.script.Scripts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The scripts the agent installed, each with an on/off switch (saved). */
public final class ScriptsScreen extends OptionsSubScreen {
    public ScriptsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("mcplink.scripts.title"));
    }

    @Override
    protected void addOptions() {
        if (!Scripts.allowed()) {
            this.list.addBig(note(Component.translatable("mcplink.scripts.off")));
        }
        List<Scripts.Info> infos = Scripts.list();
        if (infos.isEmpty()) {
            this.list.addBig(note(Component.translatable("mcplink.scripts.none")));
            return;
        }
        List<OptionInstance<?>> options = new ArrayList<>();
        for (Scripts.Info info : infos) {
            String tip = (info.description().isEmpty() ? "" : info.description() + "\n") + "Status: " + info.status();
            options.add(OptionInstance.createBoolean(info.name(), OptionInstance.cachedConstantTooltip(Component.literal(tip)),
                    info.enabled(), on -> {
                        String err = Scripts.setEnabled(info.name(), on);
                        if (err != null) McpLink.LOG.warn("Script {} did not start: {}", info.name(), err);
                    }));
        }
        this.list.addSmall(options.toArray(OptionInstance[]::new));
    }

    private static Button note(Component text) {
        Button b = Button.builder(text, x -> {
        }).width(310).build();
        b.active = false;
        return b;
    }
}
