package cc.kokodev.qe.client.mixin;

import cc.kokodev.qe.QuickExpose;
import cc.kokodev.qe.client.TunnelManager;
import com.mojang.authlib.exceptions.AuthenticationException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.OpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.NetworkUtils;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(OpenToLanScreen.class)
public abstract class OpenToLanScreenMixin extends Screen {

    @Shadow private GameMode gameMode;
    @Shadow private boolean allowCommands;

    protected OpenToLanScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void qe$addButton(CallbackInfo ci) {
        this.addDrawableChild(
            ButtonWidget.builder(
                Text.literal("Quick Expose ℹ"),
                btn -> qe$startExpose()
            )
            // Position below the standard Start / Cancel row
            .dimensions(this.width / 2 - 155, this.height / 4 + 120 + 5, 310, 20)
            .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                Text.literal("Start LAN and create a public tunnel so anyone can join.")))
            .build()
        );
    }

    @Unique
    private void qe$startExpose() {
        MinecraftClient mc = MinecraftClient.getInstance();
        IntegratedServer server = mc.getServer();
        if (server == null) {
            qe$chat(mc, "§cNot in a singleplayer world.");
            return;
        }

        int port = NetworkUtils.findLocalPort();
        GameMode gameMode = this.gameMode;
        boolean allowCommands = this.allowCommands;

        // Verify premium account off the render thread before opening LAN.
        Thread.ofVirtual().name("qe-auth").start(() -> {
            try {
                String serverId = UUID.randomUUID().toString().replace("-", "");
                mc.getSessionService().joinServer(
                    mc.getSession().getUuidOrNull(),
                    mc.getSession().getAccessToken(),
                    serverId
                );
            } catch (AuthenticationException e) {
                QuickExpose.LOGGER.info("QE auth rejected: {}", e.getMessage());
                mc.execute(() -> qe$chat(mc, "§cQuickExpose requires a premium Minecraft account."));
                return;
            } catch (Exception e) {
                QuickExpose.LOGGER.warn("QE auth check failed: {}", e.getMessage());
                mc.execute(() -> qe$chat(mc, "§cCould not verify your account — check your connection."));
                return;
            }

            mc.execute(() -> {
                boolean started = server.openToLan(gameMode, allowCommands, port);
                if (!started) {
                    qe$chat(mc, "§cFailed to start LAN server.");
                    return;
                }
                TunnelManager.INSTANCE.connect(mc.getSession().getUsername(), port, mc);
                this.close();
            });
        });
    }

    @Unique
    private static void qe$chat(MinecraftClient mc, String msg) {
        mc.execute(() -> {
            if (mc.inGameHud != null) {
                mc.inGameHud.getChatHud().addMessage(Text.literal("[QuickExpose] " + msg));
            }
        });
    }
}
