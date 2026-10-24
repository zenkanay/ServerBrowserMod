package com.serverbrowser.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ChromiumEngine implements IBrowserEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChromiumEngine.class);

    private final String executablePath;
    private final String engineName;
    private final BuiltinSearchEngine fallbackEngine = new BuiltinSearchEngine();

    public ChromiumEngine(String customPath, String name) {
        this.executablePath = customPath;
        this.engineName = name;
    }

    public static ChromiumEngine findAvailableEngine() {
        String[] candidates = {
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
            "/usr/bin/google-chrome",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        };

        for (String path : candidates) {
            if (path != null) {
                File file = new File(path);
                if (file.exists() && file.canExecute()) {
                    String name = path.toLowerCase().contains("chrome") ? "Google Chrome" : "Microsoft Edge";
                    return new ChromiumEngine(path, name);
                }
            }
        }
        return null;
    }

    @Override
    public BufferedImage renderPage(String target, int width, int height, int scrollY) throws Exception {
        if (!isAvailable()) {
            return fallbackEngine.renderPage(target, width, height, scrollY);
        }

        String url = formatUrl(target);
        Path tempDir = Files.createTempDirectory("mc_chrome_data_");
        Path tempFile = Files.createTempFile("mc_screen_", ".png");

        try {
            int captureHeight = height + Math.max(0, scrollY);
            List<String> command = new ArrayList<>();
            command.add(executablePath);
            command.add("--headless");
            command.add("--disable-gpu");
            command.add("--no-sandbox");
            command.add("--no-first-run");
            command.add("--no-default-browser-check");
            command.add("--disable-extensions");
            command.add("--disable-sync");
            command.add("--disable-background-networking");
            command.add("--user-data-dir=" + tempDir.toAbsolutePath());
            command.add("--hide-scrollbars");
            command.add("--window-size=" + width + "," + captureHeight);
            command.add("--screenshot=" + tempFile.toAbsolutePath());
            command.add(url);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 4秒でタイムアウト判定
            boolean finished = process.waitFor(4, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("Chrome timed out after 4s, instantly using fast fallback engine.");
                return fallbackEngine.renderPage(target, width, height, scrollY);
            }

            if (!Files.exists(tempFile) || Files.size(tempFile) == 0) {
                return fallbackEngine.renderPage(target, width, height, scrollY);
            }

            BufferedImage original = ImageIO.read(tempFile.toFile());
            if (original == null) {
                return fallbackEngine.renderPage(target, width, height, scrollY);
            }

            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = result.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int sy = Math.min(scrollY, Math.max(0, original.getHeight() - height));
            int sh = Math.min(height, original.getHeight() - sy);
            int sw = Math.min(width, original.getWidth());

            if (sw > 0 && sh > 0) {
                BufferedImage sub = original.getSubimage(0, sy, sw, sh);
                g.drawImage(sub, 0, 0, width, height, null);
            }
            g.dispose();

            return result;
        } catch (Exception e) {
            LOGGER.warn("Chromium render failed, falling back to builtin: {}", e.getMessage());
            return fallbackEngine.renderPage(target, width, height, scrollY);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
                deleteDirRecursively(tempDir.toFile());
            } catch (Exception ignored) {}
        }
    }

    private void deleteDirRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        file.delete();
    }

    private String formatUrl(String input) {
        String trimmed = input.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.startsWith("?")) {
            return "https://" + trimmed;
        }
        return "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
    }

    @Override
    public String getEngineName() {
        return engineName != null ? engineName : "Chromium";
    }

    @Override
    public boolean isAvailable() {
        if (executablePath == null) return false;
        File f = new File(executablePath);
        return f.exists() && f.canExecute();
    }
}
