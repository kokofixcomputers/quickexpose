package cc.kokodev.qe.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class QuickExposeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Tear down the tunnel when the player disconnects or leaves the world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            TunnelManager.INSTANCE.disconnect()
        );
    }
}
