package cc.kokodev.qe;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuickExpose implements ModInitializer {
    public static final String MOD_ID = "quickexpose";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        QuickExposeConfig.load(FabricLoader.getInstance().getConfigDir());
        LOGGER.info("QuickExpose loaded — relay: {}:{}", QuickExposeConfig.RELAY_HOST, QuickExposeConfig.CONTROL_PORT);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (server.isDedicated()) {
                ServerTunnelManager.INSTANCE.start(server.getServerPort());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server.isDedicated()) {
                ServerTunnelManager.INSTANCE.stop();
            }
        });
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}
