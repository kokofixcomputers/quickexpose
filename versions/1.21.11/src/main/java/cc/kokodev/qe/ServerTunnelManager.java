package cc.kokodev.qe;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class ServerTunnelManager {
    public static final ServerTunnelManager INSTANCE = new ServerTunnelManager();

    private static final String[] ADJECTIVES = {
        "amber", "azure", "brave", "bright", "calm", "clear", "cool", "dark",
        "deep", "fast", "fierce", "fresh", "golden", "green", "happy", "hidden",
        "iron", "jade", "kind", "large", "light", "loud", "lucky", "mighty",
        "misty", "mystic", "noble", "north", "proud", "quick", "quiet", "rapid",
        "red", "rough", "royal", "silent", "silver", "sleek", "smooth", "solar",
        "still", "stone", "strong", "swift", "tall", "teal", "tiny", "tough",
        "vivid", "warm", "wild", "wise"
    };
    private static final String[] NOUNS = {
        "arrow", "badger", "beacon", "bear", "blade", "bolt", "brook", "cave",
        "cliff", "cloud", "comet", "creek", "dawn", "deer", "dune", "eagle",
        "ember", "falcon", "fern", "flame", "flash", "fox", "frost", "gate",
        "glacier", "grove", "hawk", "hill", "hollow", "isle", "lake", "leaf",
        "lion", "marsh", "mist", "moon", "moss", "oak", "peak", "pine",
        "pond", "raven", "reef", "ridge", "river", "rock", "rose", "sage",
        "shadow", "shore", "sky", "snow", "spark", "star", "storm", "stream",
        "tiger", "vale", "vine", "wave", "wind", "wolf", "wood"
    };

    private volatile boolean active = false;
    private Thread controlThread;

    public void start(int serverPort) {
        if (active) return;
        active = true;
        String slug = (QuickExposeConfig.REMEMBER_URL && !QuickExposeConfig.LAST_SLUG.isEmpty())
                ? QuickExposeConfig.LAST_SLUG
                : randomSlug();
        controlThread = Thread.ofVirtual().name("qe-server-control").start(
                () -> controlLoop(slug, serverPort));
    }

    public void stop() {
        active = false;
        if (controlThread != null) {
            controlThread.interrupt();
            controlThread = null;
        }
    }

    private static String randomSlug() {
        Random rng = new Random();
        return ADJECTIVES[rng.nextInt(ADJECTIVES.length)] + "-" + NOUNS[rng.nextInt(NOUNS.length)];
    }

    private void controlLoop(String slug, int serverPort) {
        try (Socket sock = new Socket(QuickExposeConfig.RELAY_HOST, QuickExposeConfig.CONTROL_PORT)) {
            sock.setKeepAlive(true);

            BufferedReader in  = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter    out = new PrintWriter(
                    new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);

            out.println("REGISTER " + slug + " " + serverPort);

            String line;
            while (active && (line = in.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 0) continue;

                switch (parts[0]) {
                    case "OK" -> {
                        if (parts.length >= 2) {
                            String url = parts[1];
                            QuickExpose.LOGGER.info("************************************************************");
                            QuickExpose.LOGGER.info("* QuickExpose ready!  Players can join at:");
                            QuickExpose.LOGGER.info("*   {}:25565", url);
                            QuickExpose.LOGGER.info("************************************************************");
                            if (QuickExposeConfig.REMEMBER_URL) {
                                QuickExposeConfig.LAST_SLUG = url.contains(".") ? url.split("\\.")[0] : url;
                                QuickExposeConfig.save();
                            }
                        }
                    }
                    case "TUNNEL" -> {
                        if (parts.length >= 2) openTunnel(parts[1], serverPort);
                    }
                    case "PING" -> out.println("PONG");
                    default -> QuickExpose.LOGGER.debug("unknown relay msg: {}", line);
                }
            }
        } catch (IOException e) {
            if (active) {
                QuickExpose.LOGGER.warn("QuickExpose server tunnel lost: {}", e.getMessage());
            }
        } finally {
            active = false;
        }
    }

    private void openTunnel(String sessionId, int serverPort) {
        Thread.ofVirtual().name("qe-srv-" + sessionId).start(() -> {
            try (
                Socket relay = new Socket(QuickExposeConfig.RELAY_HOST, QuickExposeConfig.TUNNEL_PORT);
                Socket local = new Socket("127.0.0.1", serverPort)
            ) {
                relay.setTcpNoDelay(true);
                local.setTcpNoDelay(true);

                OutputStream relayOut = relay.getOutputStream();
                relayOut.write(("TUNNEL " + sessionId + "\n").getBytes(StandardCharsets.UTF_8));
                relayOut.flush();

                InputStream  relayIn  = relay.getInputStream();
                InputStream  localIn  = local.getInputStream();
                OutputStream localOut = local.getOutputStream();

                Thread.ofVirtual().name("qe-srv-r2l-" + sessionId).start(() -> {
                    try { relayIn.transferTo(localOut); } catch (IOException ignored) {}
                    closeQuietly(relay);
                    closeQuietly(local);
                });

                try { localIn.transferTo(relayOut); } catch (IOException ignored) {}
            } catch (IOException e) {
                QuickExpose.LOGGER.debug("server tunnel {} error: {}", sessionId, e.getMessage());
            }
        });
    }

    private static void closeQuietly(Closeable c) {
        try { c.close(); } catch (IOException ignored) {}
    }
}
