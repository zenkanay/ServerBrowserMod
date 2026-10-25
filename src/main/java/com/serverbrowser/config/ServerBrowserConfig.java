package com.serverbrowser.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ServerBrowser Configuration
 * Stored at config/serverbrowser.json
 */
public class ServerBrowserConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerBrowserConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("serverbrowser.json");

    private static ServerBrowserConfig INSTANCE;

    // Config options
    public String customChromePath = "";
    public String defaultUrl = "https://www.google.com";
    public int maxDisplayWidth = 100;
    public int maxDisplayHeight = 100;
    public int maxTotalScreens = 100;
    public boolean requireOpToCreate = false;
    public int liveRefreshIntervalMs = 100;
    public double maxInteractionDistance = 96.0;
    public boolean enableStealthMode = true;
    public List<String> blockedDomains = new ArrayList<>();

    // サーバー全体の最大FPS / 品質 / 解像度スケール上限 (OP設定可能)
    public int maxFps = 30;
    public int defaultFps = 14;
    public int maxQuality = 100;
    public int defaultQuality = 75;
    public double maxScale = 50.0;
    public double defaultScale = 1.0;

    public static ServerBrowserConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    public static void load() {
        File file = CONFIG_PATH.toFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                INSTANCE = GSON.fromJson(reader, ServerBrowserConfig.class);
                if (INSTANCE != null) {
                    // 以前のバージョンで20x20の低上限のまま保存されていた場合、100x100の超特大サイズへ自動拡張！
                    if (INSTANCE.maxDisplayWidth < 100) INSTANCE.maxDisplayWidth = 100;
                    if (INSTANCE.maxDisplayHeight < 100) INSTANCE.maxDisplayHeight = 100;
                    if (INSTANCE.maxInteractionDistance < 96.0) INSTANCE.maxInteractionDistance = 96.0;
                    if (INSTANCE.maxFps <= 0) INSTANCE.maxFps = 30;
                    if (INSTANCE.defaultFps <= 0) INSTANCE.defaultFps = 14;
                    if (INSTANCE.maxQuality <= 0) INSTANCE.maxQuality = 100;
                    if (INSTANCE.defaultQuality <= 0) INSTANCE.defaultQuality = 75;
                    if (INSTANCE.maxScale < 50.0) INSTANCE.maxScale = 50.0;
                    if (INSTANCE.defaultScale <= 0) INSTANCE.defaultScale = 1.0;
                    save();
                    LOGGER.info("[ServerBrowser] Configuration loaded and upgraded successfully.");
                    return;
                }
            } catch (Exception e) {
                LOGGER.error("[ServerBrowser] Failed to load config, generating defaults: {}", e.getMessage());
            }
        }

        // Generate default config
        INSTANCE = new ServerBrowserConfig();
        save();
    }

    public static void save() {
        try {
            File file = CONFIG_PATH.toFile();
            file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(INSTANCE, writer);
            }
            LOGGER.info("[ServerBrowser] Configuration saved to {}", CONFIG_PATH);
        } catch (Exception e) {
            LOGGER.error("[ServerBrowser] Failed to save config: {}", e.getMessage());
        }
    }

    public boolean isDomainBlocked(String url) {
        if (blockedDomains == null || blockedDomains.isEmpty()) return false;
        String lower = url.toLowerCase();
        for (String blocked : blockedDomains) {
            if (blocked != null && !blocked.trim().isEmpty() && lower.contains(blocked.toLowerCase().trim())) {
                return true;
            }
        }
        return false;
    }
}
