package cc.kokodev.qe.client.mixin;

import cc.kokodev.qe.QuickExpose;
import cc.kokodev.qe.client.TunnelManager;
import com.mojang.authlib.exceptions.AuthenticationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(MultiplayerOptionsScreen.class)
public abstract class MultiplayerOptionsScreenMixin extends Screen {

    @Shadow private GameType gameMode;
    @Shadow private boolean commands;

    protected MultiplayerOptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void qe$addButton(CallbackInfo ci) {
        this.addRenderableWidget(
            Button.builder(
                Component.literal("Quick Expose ℹ"),
                btn -> qe$startExpose()
            )
            .bounds(this.width / 2 - 155, this.height / 4 + 120 + 5, 310, 20)
            .tooltip(Tooltip.create(
                Component.literal("Start LAN and create a public tunnel so anyone can join.")))
            .build()
        );
    }

    @Unique
    private void qe$startExpose() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            qe$chat(mc, "§cNot in a singleplayer world.");
            return;
        }

        int port = HttpUtil.getAvailablePort();
        GameType gameMode = this.gameMode;
        boolean commands = this.commands;

        Thread.ofVirtual().name("qe-auth").start(() -> {
            try {
                String serverId = UUID.randomUUID().toString().replace("-", "");
                mc.services().sessionService().joinServer(
                    mc.getUser().getProfileId(),
                    mc.getUser().getAccessToken(),
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
                boolean started = server.publishServer(
                    MinecraftServer.MultiplayerScope.LAN, gameMode, commands, port);
                if (!started) {
                    qe$chat(mc, "§cFailed to start LAN server.");
                    return;
                }
                TunnelManager.INSTANCE.connect(mc.getUser().getName(), port, mc);
                this.onClose();
            });
        });
    }

    @Unique
    private static void qe$chat(Minecraft mc, String msg) {
        mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(
            Component.literal("[QuickExpose] " + msg)));
    }
}
