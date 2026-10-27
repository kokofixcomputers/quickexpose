package cc.kokodev.qe.client;

import cc.kokodev.qe.QuickExposeConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class QuickExposeConfigScreen extends Screen {
    private final Screen parent;
    private EditBox hostField;
    private EditBox controlPortField;
    private EditBox tunnelPortField;

    public QuickExposeConfigScreen(Screen parent) {
        super(Component.literal("QuickExpose Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y  = 50;

        // Title
        addRenderableOnly(new StringWidget(cx - 150, 15, 300, 12,
                Component.literal("QuickExpose Settings"), this.font));

        // Labels
        addRenderableOnly(new StringWidget(cx - 100, y + 6, 200, 10,
                Component.literal("Relay Host"), this.font));
        addRenderableOnly(new StringWidget(cx - 100, y + 48, 95, 10,
                Component.literal("Control Port"), this.font));
        addRenderableOnly(new StringWidget(cx + 5, y + 48, 95, 10,
                Component.literal("Tunnel Port"), this.font));

        // Inputs
        hostField = addRenderableWidget(new EditBox(
                this.font, cx - 100, y + 18, 200, 20, Component.literal("Relay Host")));
        hostField.setMaxLength(255);
        hostField.setValue(QuickExposeConfig.RELAY_HOST);

        controlPortField = addRenderableWidget(new EditBox(
                this.font, cx - 100, y + 60, 95, 20, Component.literal("Control Port")));
        controlPortField.setMaxLength(5);
        controlPortField.setValue(String.valueOf(QuickExposeConfig.CONTROL_PORT));

        tunnelPortField = addRenderableWidget(new EditBox(
                this.font, cx + 5, y + 60, 95, 20, Component.literal("Tunnel Port")));
        tunnelPortField.setMaxLength(5);
        tunnelPortField.setValue(String.valueOf(QuickExposeConfig.TUNNEL_PORT));

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> save())
                .bounds(cx - 100, y + 100, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> this.onClose())
                .bounds(cx + 5, y + 100, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset to Defaults"), btn -> resetDefaults())
                .bounds(cx - 75, y + 128, 150, 20).build());
    }

    private void save() {
        String host = hostField.getValue().trim();
        if (!host.isEmpty()) QuickExposeConfig.RELAY_HOST = host;
        try {
            int cp = Integer.parseInt(controlPortField.getValue().trim());
            if (cp > 0 && cp < 65536) QuickExposeConfig.CONTROL_PORT = cp;
        } catch (NumberFormatException ignored) {}
        try {
            int tp = Integer.parseInt(tunnelPortField.getValue().trim());
            if (tp > 0 && tp < 65536) QuickExposeConfig.TUNNEL_PORT = tp;
        } catch (NumberFormatException ignored) {}
        QuickExposeConfig.save();
        this.onClose();
    }

    private void resetDefaults() {
        QuickExposeConfig.RELAY_HOST   = QuickExposeConfig.DEFAULT_RELAY_HOST;
        QuickExposeConfig.CONTROL_PORT = QuickExposeConfig.DEFAULT_CONTROL_PORT;
        QuickExposeConfig.TUNNEL_PORT  = QuickExposeConfig.DEFAULT_TUNNEL_PORT;
        hostField.setValue(QuickExposeConfig.DEFAULT_RELAY_HOST);
        controlPortField.setValue(String.valueOf(QuickExposeConfig.DEFAULT_CONTROL_PORT));
        tunnelPortField.setValue(String.valueOf(QuickExposeConfig.DEFAULT_TUNNEL_PORT));
    }
}
