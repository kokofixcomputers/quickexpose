package cc.kokodev.qe.client;

import cc.kokodev.qe.QuickExpose;
import cc.kokodev.qe.QuickExposeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TunnelManager {
    public static final TunnelManager INSTANCE = new TunnelManager();

    private volatile boolean active = false;
    private Thread controlThread;

    public void connect(String username, int lanPort, Minecraft mc) {
        disconnect();
        active = true;
        controlThread = Thread.ofVirtual().name("qe-control").start(
                () -> controlLoop(username, lanPort, mc));
    }

    public void disconnect() {
        active = false;
        if (controlThread != null) {
            controlThread.interrupt();
            controlThread = null;
        }
    }

    private void controlLoop(String username, int lanPort, Minecraft mc) {
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
                        if (parts.length >= 2) openTunnel(parts[1], lanPort);
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

    private void openTunnel(String sessionId, int lanPort) {
        Thread.ofVirtual().name("qe-tunnel-" + sessionId).start(() -> {
            try (
                Socket relay = new Socket(QuickExposeConfig.RELAY_HOST, QuickExposeConfig.TUNNEL_PORT);
                Socket local = new Socket("127.0.0.1", lanPort)
            ) {
                relay.setTcpNoDelay(true);
                local.setTcpNoDelay(true);

                OutputStream relayOut = relay.getOutputStream();
                relayOut.write(("TUNNEL " + sessionId + "\n").getBytes(StandardCharsets.UTF_8));
                relayOut.flush();

                InputStream relayIn  = relay.getInputStream();
                InputStream localIn  = local.getInputStream();
                OutputStream localOut = local.getOutputStream();

                Thread.ofVirtual().name("qe-r2l-" + sessionId).start(() -> {
                    try { relayIn.transferTo(localOut); } catch (IOException ignored) {}
                    closeQuietly(relay);
                    closeQuietly(local);
                });

                try { localIn.transferTo(relayOut); } catch (IOException ignored) {}
            } catch (IOException e) {
                QuickExpose.LOGGER.debug("tunnel {} error: {}", sessionId, e.getMessage());
            }
        });
    }

    private static void chatInfo(Minecraft mc, String message) {
        mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("[QuickExpose] " + message)));
    }

    private static void closeQuietly(Closeable c) {
        try { c.close(); } catch (IOException ignored) {}
    }
}
