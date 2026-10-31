package cn.tohsaka.zstdnet.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Transport settings are read at startup; the HUD can also be toggled while connected. */
public record ClientConfig(boolean enabled, int level, boolean debug, boolean hud) {
    public ClientConfig(boolean enabled, int level, boolean debug) {
        this(enabled, level, debug, false);
    }

    static ClientConfig load(Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("zstdnet-proxy-client.properties");
        Properties properties = new Properties();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                Files.writeString(path, """
                    # ZstdNet Proxy Client. Changes take effect after restarting Minecraft.
                    enabled=true
                    # Client-to-proxy compression level: 1-22.
                    level=3
                    debug=false
                    # Also change this in game with /zstdhud on|off|toggle.
                    hud=false
                    """, StandardCharsets.UTF_8);
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException error) {
            logger.warn("Cannot read {}; using the default client configuration", path, error);
        }
        int level = 3;
        try {
            level = Math.clamp(Integer.parseInt(properties.getProperty("level", "3").trim()), 1, 22);
        } catch (NumberFormatException ignored) {
            logger.warn("Invalid Zstd client compression level; using 3");
        }
        return new ClientConfig(
            Boolean.parseBoolean(properties.getProperty("enabled", "true").trim()),
            level,
            Boolean.parseBoolean(properties.getProperty("debug", "false").trim()),
            Boolean.parseBoolean(properties.getProperty("hud", "false").trim())
        );
    }

    static void saveHud(boolean enabled, Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("zstdnet-proxy-client.properties");
        Properties properties = new Properties();
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
            properties.setProperty("hud", Boolean.toString(enabled));
            try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "ZstdNet Proxy Client. Transport settings apply after restarting Minecraft.");
            }
        } catch (IOException error) {
            logger.warn("Cannot save the HUD setting to {}", path, error);
        }
    }
}
