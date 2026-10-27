package cc.kokodev.qe.client;

import cc.kokodev.qe.QuickExpose;
import cc.kokodev.qe.QuickExposeConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Manages the persistent control connection to the relay server and spawns
 * a new virtual-thread tunnel pair for every incoming player session.
 */
public class TunnelManager {
    public static final TunnelManager INSTANCE = new TunnelManager();

    private volatile boolean active = false;
    private Thread controlThread;

    /** Called when the player clicks "Quick Expose". Starts the control loop. */
    public void connect(String username, int lanPort, MinecraftClient mc) {
        disconnect(); // clean up any previous session
        active = true;
        controlThread = Thread.ofVirtual().name("qe-control").start(
                () -> controlLoop(username, lanPort, mc));
    }

    /** Tears down the current session (called on world unload or disconnect). */
    public void disconnect() {
        active = false;
        if (controlThread != null) {
            controlThread.interrupt();
            controlThread = null;
        }
    }

    // -----------------------------------------------------------------------
    // Control loop
    // -----------------------------------------------------------------------

    private void controlLoop(String username, int lanPort, MinecraftClient mc) {
        try (Socket sock = new Socket(QuickExposeConfig.RELAY_HOST, QuickExposeConfig.CONTROL_PORT)) {
            sock.setKeepAlive(true);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);

            out.println("REGISTER " + username + " " + lanPort);

            String line;
            while (active && (line = in.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 0) continue;

                switch (parts[0]) {
                    case "OK" -> {
                        if (parts.length >= 2) {
                            String url = parts[1];
                            chatInfo(mc, "§aShare this address: §b" + url + ":25565");
                        }
                    }
                    case "TUNNEL" -> {
                        if (parts.length >= 2) {
                            openTunnel(parts[1], lanPort);
                        }
                    }
                    case "PING" -> out.println("PONG");
                    default -> QuickExpose.LOGGER.debug("unknown relay msg: {}", line);
                }
            }
        } catch (IOException e) {
            if (active) {
                QuickExpose.LOGGER.warn("QuickExpose control connection lost: {}", e.getMessage());
                chatInfo(mc, "§cQuickExpose: lost connection to relay (" + e.getMessage() + ")");
            }
        } finally {
            active = false;
        }
    }

    // -----------------------------------------------------------------------
    // Data tunnel
    // -----------------------------------------------------------------------

    /**
     * Opens two connections (relay data port + local LAN server) and splices
     * them. Each tunnel runs entirely in virtual threads so there is no thread
     * pool pressure regardless of how many players join.
     */
    private void openTunnel(String sessionId, int lanPort) {
        Thread.ofVirtual().name("qe-tunnel-" + sessionId).start(() -> {
            try (
                Socket relay = new Socket(QuickExposeConfig.RELAY_HOST, QuickExposeConfig.TUNNEL_PORT);
                Socket local = new Socket("127.0.0.1", lanPort)
            ) {
                relay.setTcpNoDelay(true);
                local.setTcpNoDelay(true);

                // Identify this tunnel to the relay
                OutputStream relayOut = relay.getOutputStream();
                relayOut.write(("TUNNEL " + sessionId + "\n").getBytes(StandardCharsets.UTF_8));
                relayOut.flush();

                InputStream  relayIn  = relay.getInputStream();
                InputStream  localIn  = local.getInputStream();
                OutputStream localOut = local.getOutputStream();

                // relay → local MC server
                Thread.ofVirtual().name("qe-r2l-" + sessionId).start(() -> {
                    try { relayIn.transferTo(localOut); } catch (IOException ignored) {}
                    closeQuietly(relay);
                    closeQuietly(local);
                });

                // local MC server → relay
                try { localIn.transferTo(relayOut); } catch (IOException ignored) {}

            } catch (IOException e) {
                QuickExpose.LOGGER.debug("tunnel {} error: {}", sessionId, e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void chatInfo(MinecraftClient mc, String message) {
        mc.execute(() -> {
            if (mc.inGameHud != null) {
                mc.inGameHud.getChatHud().addMessage(Text.literal("[QuickExpose] " + message));
            }
        });
    }

    private static void closeQuietly(Closeable c) {
        try { c.close(); } catch (IOException ignored) {}
    }
}
