package cc.kokodev.qe.client.mixin;

import cc.kokodev.qe.QuickExpose;
import cc.kokodev.qe.client.TunnelManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.exceptions.AuthenticationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

/**
 * Injects into multiplayerOptions so the Quick Expose button is added to the
 * LinearLayout regardless of whether PolyPlus has replaced the section.
 * Priority 1100 > PolyPlus default 1000, making this the outermost @WrapMethod.
 */
@Mixin(WorldOptionsScreen.class)
public abstract class MultiplayerOptionsScreenMixin extends Screen {

    protected MultiplayerOptionsScreenMixin(Component title) {
        super(title);
    }

    @WrapMethod(method = "multiplayerOptions")
    private void qe$wrapMultiplayerOptions(
            LinearLayout content, IntegratedServer server, Operation<Void> original) {
        // run vanilla OR PolyPlus's replacement first
        original.call(content, server);

        // only offer Quick Expose before the server is published
        if (!server.isPublished()) {
            content.addChild(
                Button.builder(
                    Component.literal("Quick Expose ℹ"),
                    btn -> qe$startExpose()
                )
                .width(308)
                .tooltip(Tooltip.create(
                    Component.literal("Start LAN and create a public tunnel so anyone can join.")))
                .build()
            );
        }
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
                    MinecraftServer.MultiplayerScope.LAN, port);
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
