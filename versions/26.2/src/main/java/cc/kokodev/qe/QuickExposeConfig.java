package cc.kokodev.qe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class QuickExposeConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("quickexpose/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String DEFAULT_RELAY_HOST   = "qe.kokodev.cc";
    public static final int    DEFAULT_CONTROL_PORT = 7000;
    public static final int    DEFAULT_TUNNEL_PORT  = 7001;

    public static String  RELAY_HOST   = DEFAULT_RELAY_HOST;
    public static int     CONTROL_PORT = DEFAULT_CONTROL_PORT;
    public static int     TUNNEL_PORT  = DEFAULT_TUNNEL_PORT;
    public static boolean REMEMBER_URL = false;
    public static String  LAST_SLUG    = "";

    private static Path configFile;

    private QuickExposeConfig() {}

    private static class Data {
        String  relayHost   = DEFAULT_RELAY_HOST;
        int     controlPort = DEFAULT_CONTROL_PORT;
        int     tunnelPort  = DEFAULT_TUNNEL_PORT;
        boolean rememberUrl = false;
        String  lastSlug    = "";
    }

    public static void load(Path configDir) {
        configFile = configDir.resolve("quickexpose.json");
        if (!Files.exists(configFile)) {
            save(); // write defaults so admins can see and edit the file
            return;
        }
        try {
            Data d = GSON.fromJson(Files.readString(configFile), Data.class);
            if (d == null) return;
            if (d.relayHost != null && !d.relayHost.isBlank()) RELAY_HOST   = d.relayHost;
            if (validPort(d.controlPort))                       CONTROL_PORT = d.controlPort;
            if (validPort(d.tunnelPort))                        TUNNEL_PORT  = d.tunnelPort;
            REMEMBER_URL = d.rememberUrl;
            if (d.lastSlug != null)                             LAST_SLUG    = d.lastSlug;
        } catch (Exception e) {
            LOGGER.warn("Failed to load config: {}", e.getMessage());
        }
    }

    public static void save() {
        if (configFile == null) return;
        Data d = new Data();
        d.relayHost   = RELAY_HOST;
        d.controlPort = CONTROL_PORT;
        d.tunnelPort  = TUNNEL_PORT;
        d.rememberUrl = REMEMBER_URL;
        d.lastSlug    = LAST_SLUG;
        try {
            Files.writeString(configFile, GSON.toJson(d));
        } catch (IOException e) {
            LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }

    private static boolean validPort(int p) {
        return p > 0 && p < 65536;
    }
}
