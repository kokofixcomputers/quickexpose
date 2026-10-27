package cc.kokodev.qe.client;

import cc.kokodev.qe.QuickExposeConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class QuickExposeConfigScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget hostField;
    private TextFieldWidget controlPortField;
    private TextFieldWidget tunnelPortField;

    public QuickExposeConfigScreen(Screen parent) {
        super(Text.literal("QuickExpose Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y  = 50;

        hostField = addDrawableChild(new TextFieldWidget(
                this.textRenderer, cx - 100, y + 18, 200, 20, Text.literal("Relay Host")));
        hostField.setMaxLength(255);
        hostField.setText(QuickExposeConfig.RELAY_HOST);

        controlPortField = addDrawableChild(new TextFieldWidget(
                this.textRenderer, cx - 100, y + 60, 95, 20, Text.literal("Control Port")));
        controlPortField.setMaxLength(5);
        controlPortField.setText(String.valueOf(QuickExposeConfig.CONTROL_PORT));

        tunnelPortField = addDrawableChild(new TextFieldWidget(
                this.textRenderer, cx + 5, y + 60, 95, 20, Text.literal("Tunnel Port")));
        tunnelPortField.setMaxLength(5);
        tunnelPortField.setText(String.valueOf(QuickExposeConfig.TUNNEL_PORT));

        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), btn -> save())
                .dimensions(cx - 100, y + 100, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> this.close())
                .dimensions(cx + 5, y + 100, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset to Defaults"), btn -> resetDefaults())
                .dimensions(cx - 75, y + 128, 150, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int y  = 50;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 20, 0xFFFFFF);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Relay Host"),   cx - 100, y + 6,  0xA0A0A0);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Control Port"), cx - 100, y + 48, 0xA0A0A0);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Tunnel Port"),  cx + 5,   y + 48, 0xA0A0A0);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void save() {
        String host = hostField.getText().trim();
        if (!host.isEmpty()) QuickExposeConfig.RELAY_HOST = host;
        try {
            int cp = Integer.parseInt(controlPortField.getText().trim());
            if (cp > 0 && cp < 65536) QuickExposeConfig.CONTROL_PORT = cp;
        } catch (NumberFormatException ignored) {}
        try {
            int tp = Integer.parseInt(tunnelPortField.getText().trim());
            if (tp > 0 && tp < 65536) QuickExposeConfig.TUNNEL_PORT = tp;
        } catch (NumberFormatException ignored) {}
        QuickExposeConfig.save();
        this.close();
    }

    private void resetDefaults() {
        QuickExposeConfig.RELAY_HOST   = QuickExposeConfig.DEFAULT_RELAY_HOST;
        QuickExposeConfig.CONTROL_PORT = QuickExposeConfig.DEFAULT_CONTROL_PORT;
        QuickExposeConfig.TUNNEL_PORT  = QuickExposeConfig.DEFAULT_TUNNEL_PORT;
        hostField.setText(QuickExposeConfig.DEFAULT_RELAY_HOST);
        controlPortField.setText(String.valueOf(QuickExposeConfig.DEFAULT_CONTROL_PORT));
        tunnelPortField.setText(String.valueOf(QuickExposeConfig.DEFAULT_TUNNEL_PORT));
    }
}
