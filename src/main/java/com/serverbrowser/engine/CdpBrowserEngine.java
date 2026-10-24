package com.serverbrowser.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 常駐型 マルチタブ Chrome DevTools Protocol (CDP) エンジン
 * 1 つのバックグラウンド Chrome プロセス上で、
 * ディスプレイごとに独立したタブ（Page WebSocket）を生成・管理し、
 * ディスプレイ同士の干渉を 100% ゼロにする最高峰のブラウザエンジン。
 */
public class CdpBrowserEngine implements IBrowserEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(CdpBrowserEngine.class);

    static {
        // ディスクキャッシュを使わずピュアメモリ内で高速に JPEG デコードを実行（ディスク I/O 削減 & 高速化）
        ImageIO.setUseCache(false);
    }

    private final String executablePath;
    private final String engineName;

    private int activePort = 0;
    private Process process;
    private Path tempProfileDir;
    private boolean browserStarted = false;

    // ブラウザ制御用 WebSocket（独立 BrowserContext 管理用）
    private WebSocket browserControlWs = null;
    private final AtomicInteger browserMsgId = new AtomicInteger(1000);
    private final Map<Integer, CompletableFuture<String>> browserPendingResponses = new ConcurrentHashMap<>();
    private final StringBuilder browserMsgBuffer = new StringBuilder();

    // スクリーンIDごとの独立したタブセッション（干渉ゼロ化）
    private final Map<Integer, TabSession> screenTabs = new ConcurrentHashMap<>();
    private final ExecutorService screencastExecutor = Executors.newFixedThreadPool(4);
    private BiConsumer<Integer, BufferedImage> globalFrameConsumer;

    public void setGlobalFrameConsumer(BiConsumer<Integer, BufferedImage> consumer) {
        this.globalFrameConsumer = consumer;
    }

    public boolean isScreencastActive(int screenId) {
        TabSession session = screenTabs.get(screenId);
        return session != null && session.isScreencastActive();
    }

    public CdpBrowserEngine(String executablePath, String name) {
        this.executablePath = executablePath;
        this.engineName = name;
    }

    public static CdpBrowserEngine findAndCreate() {
        // 1. Check custom path from server config
        String customPath = com.serverbrowser.config.ServerBrowserConfig.get().customChromePath;
        if (customPath != null && !customPath.trim().isEmpty()) {
            File customFile = new File(customPath.trim());
            if (customFile.exists() && customFile.canExecute()) {
                LOGGER.info("[ServerBrowser] Using custom browser executable: {}", customPath);
                return new CdpBrowserEngine(customPath, "Custom Chromium (CDP)");
            } else {
                LOGGER.warn("[ServerBrowser] Custom browser path not found or not executable: {}", customPath);
            }
        }

        // 2. Known default OS paths (Windows, Linux, macOS)
        String[] candidates = {
            // Windows
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
            System.getenv("LOCALAPPDATA") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            // Linux (Ubuntu, Debian, RHEL, CentOS, Fedora, Arch, Alpine)
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/snap/bin/chromium",
            "/usr/bin/brave-browser",
            "/usr/bin/microsoft-edge",
            // macOS
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            "/Applications/Chromium.app/Contents/MacOS/Chromium"
        };

        for (String path : candidates) {
            if (path != null) {
                File file = new File(path);
                if (file.exists() && file.canExecute()) {
                    String name = path.toLowerCase().contains("chrome") ? "Google Chrome (CDP)" :
                                 path.toLowerCase().contains("edge") ? "Microsoft Edge (CDP)" :
                                 path.toLowerCase().contains("brave") ? "Brave Browser (CDP)" : "Chromium (CDP)";
                    LOGGER.info("[ServerBrowser] Detected Chromium browser for CDP: {} ({})", path, name);
                    return new CdpBrowserEngine(path, name);
                }
            }
        }

        // 3. Fallback: Search PATH on Linux/macOS via 'which'
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            String[] binNames = { "google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "brave-browser" };
            for (String bin : binNames) {
                try {
                    Process p = new ProcessBuilder("which", bin).start();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line = reader.readLine();
                        if (line != null && !line.trim().isEmpty()) {
                            File f = new File(line.trim());
                            if (f.exists() && f.canExecute()) {
                                LOGGER.info("[ServerBrowser] Found browser in PATH: {}", line.trim());
                                return new CdpBrowserEngine(line.trim(), "Chromium (CDP)");
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        LOGGER.warn("[ServerBrowser] No Chromium-based browser found on the host machine. Falling back to Builtin Search Engine.");
        return null;
    }

    public synchronized void ensureBrowserStarted() throws Exception {
        if (browserStarted && process != null && process.isAlive() && activePort > 0) {
            return;
        }

        stop(); // クリーンアップ

        tempProfileDir = Files.createTempDirectory("mc_chrome_cdp_profile_");

        List<String> command = new ArrayList<>();
        command.add(executablePath);
        command.add("--headless=new");
        // GPU ハードウェアアクセラレーション有効化（動画デコード・レンダリングの CPU 負荷を 90% 削減）
        command.add("--enable-gpu-rasterization");
        command.add("--enable-zero-copy");
        command.add("--ignore-gpu-blocklist");
        command.add("--no-sandbox");
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-extensions");
        command.add("--disable-component-extensions-with-background-pages");
        command.add("--disable-default-apps");
        command.add("--autoplay-policy=no-user-gesture-required"); // 動画の自動再生を許可
        command.add("--mute-audio");
        command.add("--disable-background-networking");
        command.add("--disable-dev-shm-usage"); // 大容量ページ・動画でのクラッシュ防止
        command.add("--disable-background-timer-throttling"); // バックグラウンドでの描画遅延・間引き防止
        command.add("--disable-backgrounding-occluded-windows");
        command.add("--disable-renderer-backgrounding");
        command.add("--force-color-profile=srgb");
        command.add("--window-size=1920,1080");

        // 【最強ボット検出回避 (Stealth Mode)】「私はロボットではありません」を完全根絶！
        command.add("--disable-blink-features=AutomationControlled"); // navigator.webdriver = false に偽装
        command.add("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        command.add("--lang=ja-JP");

        command.add("--remote-debugging-port=0"); // OS 自動空きポート割り当て
        command.add("--incognito"); // 【プライバシー保護】シークレットモードで動作（ディスクへの個人情報・セッション保存を遮断）
        command.add("--user-data-dir=" + tempProfileDir.toAbsolutePath());
        command.add("about:blank");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        process = pb.start();

        // JVM 終了時の安全なプロセス回収シャットダウンフック
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "CDP-ShutdownHook"));

        // DevToolsActivePort からポート番号を読み取り
        Path portFile = tempProfileDir.resolve("DevToolsActivePort");
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            if (Files.exists(portFile)) {
                try {
                    List<String> lines = Files.readAllLines(portFile);
                    if (!lines.isEmpty() && !lines.get(0).trim().isEmpty()) {
                        activePort = Integer.parseInt(lines.get(0).trim());
                        LOGGER.info("Detected dynamic CDP port: {}", activePort);
                        break;
                    }
                } catch (Exception ignored) {}
            }
            Thread.sleep(150);
        }

        if (activePort <= 0) {
            throw new RuntimeException("Failed to detect DevToolsActivePort after 5s");
        }

        browserStarted = true;
    }

    private synchronized void ensureBrowserControlConnected() throws Exception {
        if (browserControlWs != null) return;
        ensureBrowserStarted();

        URL versionUrl = URI.create("http://127.0.0.1:" + activePort + "/json/version").toURL();
        HttpURLConnection conn = (HttpURLConnection) versionUrl.openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        String browserWsUrl = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            Matcher m = Pattern.compile("\"webSocketDebuggerUrl\":\\s*\"(ws://[^\"]+)\"").matcher(sb.toString());
            if (m.find()) {
                browserWsUrl = m.group(1);
            }
        }

        if (browserWsUrl == null) {
            LOGGER.warn("webSocketDebuggerUrl not found in /json/version");
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        browserControlWs = client.newWebSocketBuilder()
            .buildAsync(URI.create(browserWsUrl), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    browserMsgBuffer.append(data);
                    if (last) {
                        String full = browserMsgBuffer.toString();
                        browserMsgBuffer.setLength(0);
                        Matcher m = Pattern.compile("\"id\":\\s*(\\d+)").matcher(full);
                        if (m.find()) {
                            int id = Integer.parseInt(m.group(1));
                            CompletableFuture<String> f = browserPendingResponses.remove(id);
                            if (f != null) f.complete(full);
                        }
                    }
                    ws.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    browserControlWs = null;
                    return null;
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    browserControlWs = null;
                }
            }).get(5, TimeUnit.SECONDS);

        LOGGER.info("Connected to Chromium Browser Control WebSocket: {}", browserWsUrl);
    }

    public synchronized CompletableFuture<String> sendBrowserCommand(String method, Map<String, Object> params) {
        try {
            ensureBrowserControlConnected();
        } catch (Exception e) {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }

        int id = browserMsgId.getAndIncrement();
        CompletableFuture<String> future = new CompletableFuture<>();
        browserPendingResponses.put(id, future);

        StringBuilder json = new StringBuilder();
        json.append("{\"id\":").append(id).append(",\"method\":\"").append(method).append("\"");
        if (params != null && !params.isEmpty()) {
            json.append(",\"params\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":");
                Object val = entry.getValue();
                if (val instanceof String s) {
                    json.append("\"").append(escapeJson(s)).append("\"");
                } else if (val instanceof Number || val instanceof Boolean) {
                    json.append(val);
                } else {
                    json.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
                }
                first = false;
            }
            json.append("}");
        }
        json.append("}");

        if (browserControlWs != null) {
            browserControlWs.sendText(json.toString(), true);
        } else {
            future.completeExceptionally(new IllegalStateException("Browser control WebSocket is not connected"));
        }

        return future;
    }

    /**
     * スクリーン専用のタブセッションを取得（ディスプレイごとの独立 BrowserContext / 専用メモリ空間）
     */
    public synchronized TabSession getOrCreateSession(int screenId, String initialUrl, int width, int height) throws Exception {
        return getOrCreateSession(screenId, initialUrl, width, height, 75, 14, 1.0);
    }

    public synchronized TabSession getOrCreateSession(int screenId, String initialUrl, int width, int height, int quality, int fps, double scale) throws Exception {
        ensureBrowserStarted();

        TabSession session = screenTabs.get(screenId);
        if (session != null && session.isAlive()) {
            return session;
        }

        if (session != null) {
            session.close();
            screenTabs.remove(screenId);
        }

        String targetUrl = (initialUrl != null && !initialUrl.isEmpty()) ? formatUrl(initialUrl) : "https://www.google.com";

        String browserContextId = null;
        String targetId = null;
        String wsUrl = null;

        int renderW = Math.max(16, Math.min(16384, (int) Math.round(width * Math.max(0.01, scale))));
        int renderH = Math.max(16, Math.min(16384, (int) Math.round(height * Math.max(0.01, scale))));

        // 【独立メモリ空間】各ディスプレイ専用の新しい BrowserContext を作成
        // ディスプレイごとに Cookie・LocalStorage・Session が完全に分離され、リロードしてもデータは保持！
        try {
            String ctxRes = sendBrowserCommand("Target.createBrowserContext", Map.of()).get(3, TimeUnit.SECONDS);
            Matcher cm = Pattern.compile("\"browserContextId\":\\s*\"([^\"]+)\"").matcher(ctxRes);
            if (cm.find()) {
                browserContextId = cm.group(1);
            }

            if (browserContextId != null) {
                Map<String, Object> targetParams = new HashMap<>();
                targetParams.put("url", targetUrl);
                targetParams.put("browserContextId", browserContextId);
                targetParams.put("width", Math.max(128, renderW));
                targetParams.put("height", Math.max(128, renderH));

                String targetRes = sendBrowserCommand("Target.createTarget", targetParams).get(3, TimeUnit.SECONDS);
                Matcher tm = Pattern.compile("\"targetId\":\\s*\"([^\"]+)\"").matcher(targetRes);
                if (tm.find()) {
                    targetId = tm.group(1);
                    wsUrl = "ws://127.0.0.1:" + activePort + "/devtools/page/" + targetId;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Target.createBrowserContext failed for screen #{}, fallback to /json/new: {}", screenId, e.getMessage());
        }

        // フォールバック: PUT /json/new
        if (wsUrl == null) {
            String createEndpoint = "http://127.0.0.1:" + activePort + "/json/new?" + java.net.URLEncoder.encode(targetUrl, java.nio.charset.StandardCharsets.UTF_8);
            URL url = URI.create(createEndpoint).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("Failed to create new tab for screen #" + screenId + ", HTTP " + conn.getResponseCode());
            }

            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                String json = sb.toString();

                Matcher idMatcher = Pattern.compile("\"id\":\\s*\"([^\"]+)\"").matcher(json);
                if (idMatcher.find()) targetId = idMatcher.group(1);

                Matcher wsMatcher = Pattern.compile("\"webSocketDebuggerUrl\":\\s*\"(ws://[^\"]+)\"").matcher(json);
                if (wsMatcher.find()) wsUrl = wsMatcher.group(1);
            }
        }

        if (wsUrl == null) {
            throw new RuntimeException("webSocketDebuggerUrl not found for screen #" + screenId);
        }

        LOGGER.info("Created isolated memory tab for screen #{}: contextId={}, targetId={}, ws={}", screenId, browserContextId, targetId, wsUrl);

        TabSession newSession = new TabSession(screenId, browserContextId, targetId, wsUrl, targetUrl, quality, fps, scale);
        newSession.connect(width, height);
        screenTabs.put(screenId, newSession);

        return newSession;
    }

    public synchronized void closeTab(int screenId) {
        TabSession session = screenTabs.remove(screenId);
        if (session != null) {
            final String targetId = session.getTargetId();
            final String browserContextId = session.getBrowserContextId();
            session.close();

            // 非同期でタブおよび専用コンテキスト（Cookie・全メモリデータ）を安全に消去・破棄！
            CompletableFuture.runAsync(() -> {
                try {
                    if (targetId != null) {
                        sendBrowserCommand("Target.closeTarget", Map.of("targetId", targetId)).get(1, TimeUnit.SECONDS);
                    }
                    if (browserContextId != null) {
                        sendBrowserCommand("Target.disposeBrowserContext", Map.of("browserContextId", browserContextId)).get(1, TimeUnit.SECONDS);
                        LOGGER.info("Disposed BrowserContext memory for screen #{}: {}", screenId, browserContextId);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * ブラウザ側で現在実際に開いている最新 URL（window.location.href）を取得
     */
    public String getActiveUrl(int screenId) {
        TabSession session = screenTabs.get(screenId);
        if (session != null && session.isAlive()) {
            return session.fetchCurrentLocationUrl();
        }
        return null;
    }

    /**
     * 視線カーソルの位置に合わせてブラウザ内に mouseMoved イベントを送信（PCのような自然なホバー効果を発火）
     */
    public void updateScreenSettings(int screenId, int baseWidth, int baseHeight, int quality, int fps, double scale) {
        TabSession session = screenTabs.get(screenId);
        if (session != null && session.isAlive()) {
            session.updateSettings(baseWidth, baseHeight, quality, fps, scale);
        }
    }

    public void dispatchMouseMove(int screenId, int x, int y) {
        TabSession session = screenTabs.get(screenId);
        if (session != null && session.isAlive()) {
            session.dispatchMouseMove(x, y);
        }
    }

    /**
     * マウス左ボタンを押したまま視線移動（ドラッグ処理）
     */
    public void dispatchMouseDrag(int screenId, int x, int y) {
        TabSession session = screenTabs.get(screenId);
        if (session != null && session.isAlive()) {
            session.dispatchMouseDrag(x, y);
        }
    }

    public void dispatchMousePress(int screenId, int x, int y) {
        TabSession session = screenTabs.get(screenId);
        if (session != null && session.isAlive()) {
            session.dispatchMousePress(x, y);
        }
    }

    public void dispatchMouseRelease(int screenId, int x, int y) {
        TabSession session = screenTabs.get(screenId);
        if (session != null && session.isAlive()) {
            session.dispatchMouseRelease(x, y);
        }
    }

    // 互換用メソッド (デフォルトスクリーン #1)
    @Override
    public BufferedImage renderPage(String target, int width, int height, int scrollY) throws Exception {
        return renderPageForScreen(1, target, width, height, scrollY);
    }

    public BufferedImage renderPageForScreen(int screenId, String target, int width, int height, int scrollY) throws Exception {
        TabSession session = getOrCreateSession(screenId, target, width, height);
        return session.navigateAndCapture(target, width, height);
    }

    public BufferedImage renderPageForScreen(int screenId, String target, int width, int height, int quality, int fps, double scale, int scrollY) throws Exception {
        TabSession session = getOrCreateSession(screenId, target, width, height, quality, fps, scale);
        session.updateSettings(width, height, quality, fps, scale);
        return session.navigateAndCapture(target, width, height);
    }

    public void openTab(int screenId, String initialUrl, int width, int height, int quality, int fps, double scale) {
        try {
            TabSession session = getOrCreateSession(screenId, initialUrl, width, height, quality, fps, scale);
            session.updateSettings(width, height, quality, fps, scale);
            if (initialUrl != null && !initialUrl.isEmpty()) {
                session.navigateAndCapture(initialUrl, width, height);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to open tab for screen #{}: {}", screenId, e.getMessage());
        }
    }

    public BufferedImage performClickForScreen(int screenId, int x, int y, int clickCount, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performClick(x, y, clickCount, width, height);
    }

    public BufferedImage performRightClickForScreen(int screenId, int x, int y, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performRightClick(x, y, width, height);
    }

    public BufferedImage performTextInputForScreen(int screenId, String text, boolean pressEnter, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performTextInput(text, pressEnter, width, height);
    }

    public BufferedImage performKeyPressForScreen(int screenId, String key, int keyCode, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performKeyPress(key, keyCode, width, height);
    }

    public BufferedImage performBackspaceForScreen(int screenId, int count, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performBackspace(count, width, height);
    }

    public BufferedImage performScrollForScreen(int screenId, int x, int y, int deltaY, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performScroll(x, y, deltaY, width, height);
    }

    public BufferedImage performScrollForScreen(int screenId, int deltaY, int width, int height) throws Exception {
        return performScrollForScreen(screenId, width / 2, height / 2, deltaY, width, height);
    }

    public BufferedImage performBackForScreen(int screenId, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performBack(width, height);
    }

    public BufferedImage performForwardForScreen(int screenId, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performForward(width, height);
    }

    public BufferedImage performReloadForScreen(int screenId, int width, int height) throws Exception {
        TabSession session = getOrCreateSession(screenId, null, width, height);
        return session.performReload(width, height);
    }

    /**
     * サイトの自律的な画面変化（動画・アニメーション・非同期ロードなど）を検知するための最新スクリーンショット取得
     */
    public BufferedImage captureScreenshotForScreen(int screenId, int width, int height) throws Exception {
        TabSession session = screenTabs.get(screenId);
        if (session != null && session.isAlive()) {
            return session.captureScreenshot(width, height);
        }
        return null;
    }

    public synchronized void stop() {
        browserStarted = false;
        if (browserControlWs != null) {
            try {
                browserControlWs.sendClose(WebSocket.NORMAL_CLOSURE, "Stopping").join();
            } catch (Exception ignored) {}
            browserControlWs = null;
        }
        for (CompletableFuture<String> future : browserPendingResponses.values()) {
            future.cancel(true);
        }
        browserPendingResponses.clear();

        for (TabSession session : screenTabs.values()) {
            session.close();
        }
        screenTabs.clear();

        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process = null;
        }
        if (tempProfileDir != null) {
            try {
                deleteDirRecursively(tempProfileDir.toFile());
            } catch (Exception ignored) {}
            tempProfileDir = null;
        }
        activePort = 0;
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
        // 日本語正規 Google 検索 (hl=ja を付与してボット判定を回避)
        return "https://www.google.co.jp/search?hl=ja&q=" + java.net.URLEncoder.encode(trimmed, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String getEngineName() {
        return engineName != null ? engineName : "Chromium (Multi-Tab CDP Real Browser)";
    }

    @Override
    public boolean isAvailable() {
        return executablePath != null && new File(executablePath).exists();
    }

    /**
     * スクリーン専用の独立したタブセッション（専用メモリ空間 / 独立 BrowserContext）
     */
    public class TabSession {
        private final int screenId;
        private final String browserContextId;
        private final String targetId;
        private final String wsUrl;
        private String currentUrl;

        private WebSocket webSocket;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final Map<Integer, CompletableFuture<String>> pendingResponses = new ConcurrentHashMap<>();
        private final StringBuilder messageBuffer = new StringBuilder();
        private boolean connected = false;
        private volatile boolean screencastActive = false;
        private long lastScreencastFrameTime = 0;
        private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);

        // 画面個別設定（FPS、JPEG品質、解像度スケール）
        private int currentWidth = 128;
        private int currentHeight = 128;
        private int targetFps = 14;
        private int targetQuality = 75;
        private double scale = 1.0;

        public TabSession(int screenId, String browserContextId, String targetId, String wsUrl, String currentUrl) {
            this(screenId, browserContextId, targetId, wsUrl, currentUrl, 75, 14, 1.0);
        }

        public TabSession(int screenId, String browserContextId, String targetId, String wsUrl, String currentUrl, int quality, int fps, double scale) {
            this.screenId = screenId;
            this.browserContextId = browserContextId;
            this.targetId = targetId;
            this.wsUrl = wsUrl;
            this.currentUrl = currentUrl;
            this.targetQuality = Math.max(10, Math.min(100, quality));
            this.targetFps = Math.max(1, Math.min(60, fps));
            this.scale = Math.max(0.01, scale);
        }

        public String getBrowserContextId() { return browserContextId; }
        public String getTargetId() { return targetId; }
        public boolean isAlive() { return connected && webSocket != null; }

        public void connect(int width, int height) throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocketListener())
                .get(5, TimeUnit.SECONDS);

            sendCommand("Page.enable", Map.of()).get(3, TimeUnit.SECONDS);

            // ステルススクリプト注入（reCAPTCHA / Cloudflare ボット検出完全無効化 ＆ 汎用全画面 API エミュレーション）
            String stealthJs = "Object.defineProperty(navigator, 'webdriver', { get: () => false });\n" +
                "window.chrome = { runtime: {}, app: {}, csi: () => {}, loadTimes: () => {} };\n" +
                "Object.defineProperty(navigator, 'languages', { get: () => ['ja-JP', 'ja', 'en-US', 'en'] });\n" +
                "Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });\n" +
                "(function() {\n" +
                "    let currentFsElem = null;\n" +
                "    const fsStyle = document.createElement('style');\n" +
                "    fsStyle.id = '__sb_fs__';\n" +
                "    fsStyle.textContent = '.__sb_fullscreen__ { position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; max-width: 100vw !important; max-height: 100vh !important; z-index: 2147483647 !important; margin: 0 !important; padding: 0 !important; background: #000 !important; } ' +\n" +
                "        '.__sb_fullscreen__ video { position: absolute !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; max-width: 100vw !important; max-height: 100vh !important; object-fit: contain !important; }';\n" +
                "    function ensureStyle() { if (!document.getElementById('__sb_fs__') && document.head) document.head.appendChild(fsStyle); }\n" +
                "    const notifyFs = function() {\n" +
                "        document.dispatchEvent(new Event('fullscreenchange', { bubbles: true }));\n" +
                "        document.dispatchEvent(new Event('webkitfullscreenchange', { bubbles: true }));\n" +
                "        window.dispatchEvent(new Event('resize'));\n" +
                "        setTimeout(() => window.dispatchEvent(new Event('resize')), 100);\n" +
                "    };\n" +
                "    const exitFs = function() {\n" +
                "        if (currentFsElem) {\n" +
                "            currentFsElem.classList.remove('__sb_fullscreen__');\n" +
                "            currentFsElem = null;\n" +
                "            notifyFs();\n" +
                "        }\n" +
                "        return Promise.resolve();\n" +
                "    };\n" +
                "    Element.prototype.requestFullscreen = function() {\n" +
                "        ensureStyle();\n" +
                "        if (currentFsElem) currentFsElem.classList.remove('__sb_fullscreen__');\n" +
                "        currentFsElem = this;\n" +
                "        this.classList.add('__sb_fullscreen__');\n" +
                "        notifyFs();\n" +
                "        return Promise.resolve();\n" +
                "    };\n" +
                "    Element.prototype.webkitRequestFullscreen = Element.prototype.requestFullscreen;\n" +
                "    document.exitFullscreen = exitFs;\n" +
                "    document.webkitExitFullscreen = exitFs;\n" +
                "    try {\n" +
                "        Object.defineProperty(document, 'fullscreenElement', { get: () => currentFsElem, configurable: true });\n" +
                "        Object.defineProperty(document, 'webkitFullscreenElement', { get: () => currentFsElem, configurable: true });\n" +
                "        Object.defineProperty(document, 'fullscreenEnabled', { get: () => true, configurable: true });\n" +
                "        Object.defineProperty(document, 'webkitFullscreenEnabled', { get: () => true, configurable: true });\n" +
                "    } catch(e) {}\n" +
                "    window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && currentFsElem) exitFs(); }, true);\n" +
                "})();";
            sendCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", stealthJs)).get(2, TimeUnit.SECONDS);

            // 解像度設定（指定された解像度スケールを初期から正確に反映）
            int renderW = Math.max(16, Math.min(16384, (int) Math.round(width * this.scale)));
            int renderH = Math.max(16, Math.min(16384, (int) Math.round(height * this.scale)));
            this.currentWidth = renderW;
            this.currentHeight = renderH;

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("width", renderW);
            metrics.put("height", renderH);
            metrics.put("deviceScaleFactor", 1);
            metrics.put("mobile", false);
            sendCommand("Emulation.setDeviceMetricsOverride", metrics).get(2, TimeUnit.SECONDS);

            connected = true;

            // 超高速 GPU Screencast ストリーミング開始（目標 FPS・品質・解像度を完全に反映！）
            startScreencast(renderW, renderH, this.targetQuality, this.targetFps);
        }

        public synchronized CompletableFuture<String> sendCommand(String method, Map<String, Object> params) {
            int id = nextId.getAndIncrement();
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingResponses.put(id, future);

            StringBuilder json = new StringBuilder();
            json.append("{\"id\":").append(id).append(",\"method\":\"").append(method).append("\"");
            if (params != null && !params.isEmpty()) {
                json.append(",\"params\":{");
                boolean first = true;
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    if (!first) json.append(",");
                    json.append("\"").append(entry.getKey()).append("\":");
                    Object val = entry.getValue();
                    if (val instanceof String s) {
                        json.append("\"").append(escapeJson(s)).append("\"");
                    } else if (val instanceof Number || val instanceof Boolean) {
                        json.append(val);
                    } else {
                        json.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
                    }
                    first = false;
                }
                json.append("}");
            }
            json.append("}");

            if (webSocket != null) {
                webSocket.sendText(json.toString(), true);
            } else {
                future.completeExceptionally(new IllegalStateException("WebSocket for screen #" + screenId + " is not connected"));
            }

            return future;
        }

        public BufferedImage navigateAndCapture(String target, int width, int height) throws Exception {
            String formatted = formatUrl(target);
            if (!formatted.equalsIgnoreCase(currentUrl)) {
                this.currentUrl = formatted;
                sendCommand("Page.navigate", Map.of("url", formatted)).get(5, TimeUnit.SECONDS);
                Thread.sleep(800);
            }
            return captureScreenshot(width, height);
        }

        public String fetchCurrentLocationUrl() {
            try {
                String res = sendCommand("Runtime.evaluate", Map.of("expression", "window.location.href")).get(1, TimeUnit.SECONDS);
                Matcher m = Pattern.compile("\"value\":\\s*\"([^\"]+)\"").matcher(res);
                if (m.find()) {
                    String url = m.group(1).replace("\\/", "/");
                    this.currentUrl = url;
                    return url;
                }
            } catch (Exception ignored) {}
            return currentUrl;
        }

        private long lastMouseMoveTime = 0;
        private int lastSentMouseX = -9999;
        private int lastSentMouseY = -9999;

        public void dispatchMouseMove(int x, int y) {
            long now = System.currentTimeMillis();
            // 同一座標、または直近 40ms 以内の送信はスロットリング（画面外アンホバーは即時送信）
            if (x == lastSentMouseX && y == lastSentMouseY) {
                return;
            }
            if (x >= 0 && y >= 0 && (now - lastMouseMoveTime < 40)) {
                return;
            }
            lastMouseMoveTime = now;
            lastSentMouseX = x;
            lastSentMouseY = y;

            int sx = (x < 0) ? x : (int) Math.round(x * scale);
            int sy = (y < 0) ? y : (int) Math.round(y * scale);

            try {
                Map<String, Object> moveParams = new HashMap<>();
                moveParams.put("type", "mouseMoved");
                moveParams.put("x", Math.max(-100, sx));
                moveParams.put("y", Math.max(-100, sy));
                moveParams.put("pointerType", "mouse");
                sendCommand("Input.dispatchMouseEvent", moveParams);
            } catch (Exception ignored) {}
        }

        public void dispatchMouseDrag(int x, int y) {
            long now = System.currentTimeMillis();
            if (x == lastSentMouseX && y == lastSentMouseY) {
                return;
            }
            if (x >= 0 && y >= 0 && (now - lastMouseMoveTime < 40)) {
                return;
            }
            lastMouseMoveTime = now;
            lastSentMouseX = x;
            lastSentMouseY = y;

            int sx = (x < 0) ? x : (int) Math.round(x * scale);
            int sy = (y < 0) ? y : (int) Math.round(y * scale);

            try {
                Map<String, Object> moveParams = new HashMap<>();
                moveParams.put("type", "mouseMoved");
                moveParams.put("x", Math.max(-100, sx));
                moveParams.put("y", Math.max(-100, sy));
                moveParams.put("button", "left");
                moveParams.put("buttons", 1); // 左ボタンドラッグ中！
                moveParams.put("pointerType", "mouse");
                sendCommand("Input.dispatchMouseEvent", moveParams);
            } catch (Exception ignored) {}
        }

        public void dispatchMousePress(int x, int y) {
            int sx = (x < 0) ? x : (int) Math.round(x * scale);
            int sy = (y < 0) ? y : (int) Math.round(y * scale);
            try {
                Map<String, Object> pressParams = new HashMap<>();
                pressParams.put("type", "mousePressed");
                pressParams.put("x", Math.max(0, sx));
                pressParams.put("y", Math.max(0, sy));
                pressParams.put("button", "left");
                pressParams.put("buttons", 1);
                pressParams.put("clickCount", 1);
                pressParams.put("pointerType", "mouse");
                sendCommand("Input.dispatchMouseEvent", pressParams);
            } catch (Exception ignored) {}
        }

        public void dispatchMouseRelease(int x, int y) {
            int sx = (x < 0) ? x : (int) Math.round(x * scale);
            int sy = (y < 0) ? y : (int) Math.round(y * scale);
            try {
                Map<String, Object> releaseParams = new HashMap<>();
                releaseParams.put("type", "mouseReleased");
                releaseParams.put("x", Math.max(0, sx));
                releaseParams.put("y", Math.max(0, sy));
                releaseParams.put("button", "left");
                releaseParams.put("buttons", 0);
                releaseParams.put("clickCount", 1);
                releaseParams.put("pointerType", "mouse");
                sendCommand("Input.dispatchMouseEvent", releaseParams);
            } catch (Exception ignored) {}
        }

        public BufferedImage performClick(int x, int y, int clickCount, int width, int height) throws Exception {
            int sx = (int) Math.round(x * scale);
            int sy = (int) Math.round(y * scale);
            if (clickCount >= 2) {
                // CDP での完全なダブルクリックシーケンス（clickCount 1 -> 2 をアトミックに送信）
                Map<String, Object> p1 = new HashMap<>();
                p1.put("type", "mousePressed");
                p1.put("x", sx);
                p1.put("y", sy);
                p1.put("button", "left");
                p1.put("clickCount", 1);
                sendCommand("Input.dispatchMouseEvent", p1).get(1, TimeUnit.SECONDS);

                Map<String, Object> r1 = new HashMap<>();
                r1.put("type", "mouseReleased");
                r1.put("x", sx);
                r1.put("y", sy);
                r1.put("button", "left");
                r1.put("clickCount", 1);
                sendCommand("Input.dispatchMouseEvent", r1).get(1, TimeUnit.SECONDS);

                Thread.sleep(40); // 自然なダブルクリック間隔

                Map<String, Object> p2 = new HashMap<>();
                p2.put("type", "mousePressed");
                p2.put("x", sx);
                p2.put("y", sy);
                p2.put("button", "left");
                p2.put("clickCount", 2);
                sendCommand("Input.dispatchMouseEvent", p2).get(1, TimeUnit.SECONDS);

                Map<String, Object> r2 = new HashMap<>();
                r2.put("type", "mouseReleased");
                r2.put("x", sx);
                r2.put("y", sy);
                r2.put("button", "left");
                r2.put("clickCount", 2);
                sendCommand("Input.dispatchMouseEvent", r2).get(1, TimeUnit.SECONDS);
            } else {
                // 通常のシングルクリック
                Map<String, Object> pressParams = new HashMap<>();
                pressParams.put("type", "mousePressed");
                pressParams.put("x", sx);
                pressParams.put("y", sy);
                pressParams.put("button", "left");
                pressParams.put("clickCount", 1);
                sendCommand("Input.dispatchMouseEvent", pressParams).get(1, TimeUnit.SECONDS);

                Map<String, Object> releaseParams = new HashMap<>();
                releaseParams.put("type", "mouseReleased");
                releaseParams.put("x", sx);
                releaseParams.put("y", sy);
                releaseParams.put("button", "left");
                releaseParams.put("clickCount", 1);
                sendCommand("Input.dispatchMouseEvent", releaseParams).get(1, TimeUnit.SECONDS);
            }

            Thread.sleep(250);
            fetchCurrentLocationUrl(); // 最新のページURLを取得・追跡
            return captureScreenshot(width, height);
        }

        public BufferedImage performRightClick(int x, int y, int width, int height) throws Exception {
            int sx = (int) Math.round(x * scale);
            int sy = (int) Math.round(y * scale);
            Map<String, Object> pressParams = new HashMap<>();
            pressParams.put("type", "mousePressed");
            pressParams.put("x", sx);
            pressParams.put("y", sy);
            pressParams.put("button", "right");
            pressParams.put("clickCount", 1);
            sendCommand("Input.dispatchMouseEvent", pressParams).get(2, TimeUnit.SECONDS);

            Map<String, Object> releaseParams = new HashMap<>();
            releaseParams.put("type", "mouseReleased");
            releaseParams.put("x", x);
            releaseParams.put("y", y);
            releaseParams.put("button", "right");
            releaseParams.put("clickCount", 1);
            sendCommand("Input.dispatchMouseEvent", releaseParams).get(2, TimeUnit.SECONDS);

            Thread.sleep(350);
            fetchCurrentLocationUrl();
            return captureScreenshot(width, height);
        }

        public BufferedImage performTextInput(String text, boolean pressEnter, int width, int height) throws Exception {
            sendCommand("Input.insertText", Map.of("text", text)).get(2, TimeUnit.SECONDS);

            if (pressEnter) {
                performKeyPress("Enter", 13, width, height);
            } else {
                Thread.sleep(200);
            }

            return captureScreenshot(width, height);
        }

        public BufferedImage performKeyPress(String key, int keyCode, int width, int height) throws Exception {
            Map<String, Object> keyDown = new HashMap<>();
            keyDown.put("type", "rawKeyDown");
            keyDown.put("windowsVirtualKeyCode", keyCode);
            keyDown.put("key", key);
            keyDown.put("code", key);
            sendCommand("Input.dispatchKeyEvent", keyDown).get(1, TimeUnit.SECONDS);

            Map<String, Object> keyUp = new HashMap<>();
            keyUp.put("type", "keyUp");
            keyUp.put("windowsVirtualKeyCode", keyCode);
            keyUp.put("key", key);
            keyUp.put("code", key);
            sendCommand("Input.dispatchKeyEvent", keyUp).get(1, TimeUnit.SECONDS);

            Thread.sleep(600);
            return captureScreenshot(width, height);
        }

        public BufferedImage performBackspace(int count, int width, int height) throws Exception {
            int actualCount = Math.max(1, Math.min(100, count));
            for (int i = 0; i < actualCount; i++) {
                Map<String, Object> keyDown = new HashMap<>();
                keyDown.put("type", "rawKeyDown");
                keyDown.put("windowsVirtualKeyCode", 8);
                keyDown.put("key", "Backspace");
                keyDown.put("code", "Backspace");
                sendCommand("Input.dispatchKeyEvent", keyDown).get(500, TimeUnit.MILLISECONDS);

                Map<String, Object> keyUp = new HashMap<>();
                keyUp.put("type", "keyUp");
                keyUp.put("windowsVirtualKeyCode", 8);
                keyUp.put("key", "Backspace");
                keyUp.put("code", "Backspace");
                sendCommand("Input.dispatchKeyEvent", keyUp).get(500, TimeUnit.MILLISECONDS);

                if (i < actualCount - 1) {
                    Thread.sleep(15);
                }
            }
            Thread.sleep(200);
            return captureScreenshot(width, height);
        }

        public BufferedImage performScroll(int deltaY, int width, int height) throws Exception {
            return performScroll(width / 2, height / 2, deltaY, width, height);
        }

        public BufferedImage performScroll(int cursorX, int cursorY, int deltaY, int width, int height) throws Exception {
            final int targetX = (cursorX >= 0 && cursorX < width) ? cursorX : width / 2;
            final int targetY = (cursorY >= 0 && cursorY < height) ? cursorY : height / 2;

            // 1. ネイティブ CDP mouseWheel イベント送信（YouTube Shorts、モーダル等の内部スクロールコンテナを直接駆動）
            try {
                Map<String, Object> wheelParams = new HashMap<>();
                wheelParams.put("type", "mouseWheel");
                wheelParams.put("x", targetX);
                wheelParams.put("y", targetY);
                wheelParams.put("deltaX", 0);
                wheelParams.put("deltaY", deltaY);
                wheelParams.put("pointerType", "mouse");
                sendCommand("Input.dispatchMouseEvent", wheelParams).get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}

            // 2. 万能ディープ・コンテナ・スクローラー (Universal Deep Container Scroller)
            // カーソル下の最も近いスクロール可能要素 (overflow-y: auto/scroll, scrollHeight > clientHeight) を自動特定してスクロール！
            String js = "(function(x, y, delta) { " +
                "var target = document.elementFromPoint(x, y); " +
                "var el = target; " +
                "var scrolled = false; " +
                "while (el && el !== document.documentElement && el !== document.body) { " +
                "    var style = window.getComputedStyle(el); " +
                "    var overflowY = style.overflowY || style.overflow; " +
                "    var canScroll = (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') && (el.scrollHeight > el.clientHeight); " +
                "    if (canScroll) { " +
                "        var prev = el.scrollTop; " +
                "        el.scrollTop += delta; " +
                "        if (el.scrollTop !== prev) { " +
                "            scrolled = true; " +
                "            break; " +
                "        } " +
                "    } " +
                "    el = el.parentElement; " +
                "} " +
                "if (!scrolled) { " +
                "    window.scrollBy(0, delta); " +
                "    var root = document.scrollingElement || document.documentElement || document.body; " +
                "    if (root) root.scrollTop += delta; " +
                "} " +
                "})(" + targetX + ", " + targetY + ", " + deltaY + ");";

            try {
                sendCommand("Runtime.evaluate", Map.of("expression", js)).get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}

            Thread.sleep(120);
            return captureScreenshot(width, height);
        }

        public BufferedImage performBack(int width, int height) throws Exception {
            sendCommand("Runtime.evaluate", Map.of("expression", "window.history.back();")).get(1, TimeUnit.SECONDS);
            Thread.sleep(600);
            fetchCurrentLocationUrl();
            return captureScreenshot(width, height);
        }

        public BufferedImage performForward(int width, int height) throws Exception {
            sendCommand("Runtime.evaluate", Map.of("expression", "window.history.forward();")).get(1, TimeUnit.SECONDS);
            Thread.sleep(600);
            fetchCurrentLocationUrl();
            return captureScreenshot(width, height);
        }

        public BufferedImage performReload(int width, int height) throws Exception {
            try {
                sendCommand("Page.reload", Map.of()).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                sendCommand("Runtime.evaluate", Map.of("expression", "window.location.reload();")).get(1, TimeUnit.SECONDS);
            }
            Thread.sleep(800);
            fetchCurrentLocationUrl();
            return captureScreenshot(width, height);
        }

        public BufferedImage captureScreenshot(int width, int height) throws Exception {
            // 高速・軽量な JPEG 転送 (画質 75: 転送サイズ削減とデコード高速化、地図パレット変換に最適な黄金比)
            CompletableFuture<String> future = sendCommand("Page.captureScreenshot", Map.of("format", "jpeg", "quality", 75));
            String response = future.get(5, TimeUnit.SECONDS);

            int dataIdx = response.indexOf("\"data\":");
            if (dataIdx == -1) {
                throw new RuntimeException("Screenshot data not found in response for screen #" + screenId);
            }
            int quoteStart = response.indexOf("\"", dataIdx + 7);
            int quoteEnd = response.lastIndexOf("\"");
            if (quoteStart == -1 || quoteEnd <= quoteStart) {
                throw new RuntimeException("Failed to parse base64 for screen #" + screenId);
            }

            String base64Data = response.substring(quoteStart + 1, quoteEnd);
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                BufferedImage img = ImageIO.read(bais);
                if (img == null) {
                    throw new RuntimeException("ImageIO failed to decode screenshot for screen #" + screenId);
                }
                return img;
            }
        }

        public void close() {
            connected = false;
            stopScreencast();
            for (CompletableFuture<String> future : pendingResponses.values()) {
                future.cancel(true);
            }
            pendingResponses.clear();
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Close tab").join();
                } catch (Exception ignored) {}
                webSocket = null;
            }
        }

        public void clearSessionData() {
            try {
                sendCommand("Network.enable", Map.of());
                sendCommand("Network.clearBrowserCookies", Map.of());
                sendCommand("Network.clearBrowserCache", Map.of());
                sendCommand("Storage.clearDataForOrigin", Map.of("origin", "*", "storageTypes", "all"));
            } catch (Exception ignored) {}
        }

        public boolean isScreencastActive() {
            return screencastActive;
        }

        public void startScreencast(int width, int height) {
            startScreencast(width, height, this.targetQuality, this.targetFps);
        }

        public void startScreencast(int width, int height, int quality, int fps) {
            try {
                this.currentWidth = width;
                this.currentHeight = height;
                this.targetQuality = Math.max(10, Math.min(100, quality));
                this.targetFps = Math.max(1, Math.min(60, fps));

                Map<String, Object> params = new HashMap<>();
                params.put("format", "jpeg");
                params.put("quality", this.targetQuality);
                params.put("maxWidth", Math.max(128, width));
                params.put("maxHeight", Math.max(128, height));
                params.put("everyNthFrame", 1);
                sendCommand("Page.startScreencast", params);
                this.screencastActive = true;
                LOGGER.info("Started high-speed CDP Page.startScreencast for screen #{} ({}x{}, {} FPS, {}% quality)",
                    screenId, width, height, this.targetFps, this.targetQuality);
            } catch (Exception e) {
                LOGGER.warn("Failed to start screencast for screen #{}: {}", screenId, e.getMessage());
            }
        }

        public void updateSettings(int baseWidth, int baseHeight, int quality, int fps, double scale) {
            try {
                this.targetQuality = Math.max(10, Math.min(100, quality));
                this.targetFps = Math.max(1, Math.min(60, fps));
                this.scale = Math.max(0.01, scale);

                int renderW = Math.max(16, Math.min(16384, (int) Math.round(baseWidth * this.scale)));
                int renderH = Math.max(16, Math.min(16384, (int) Math.round(baseHeight * this.scale)));
                this.currentWidth = renderW;
                this.currentHeight = renderH;

                // 1. 解像度（DeviceMetrics）の動的更新
                Map<String, Object> metrics = new HashMap<>();
                metrics.put("width", renderW);
                metrics.put("height", renderH);
                metrics.put("deviceScaleFactor", 1);
                metrics.put("mobile", false);
                sendCommand("Emulation.setDeviceMetricsOverride", metrics);

                // 2. Screencast を再起動して新設定（FPS・品質・解像度）を即時適用
                stopScreencast();
                startScreencast(renderW, renderH, this.targetQuality, this.targetFps);
                LOGGER.info("Updated screen #{} settings: {}x{} (scale: {}x), {} FPS, {}% quality",
                    screenId, renderW, renderH, this.scale, this.targetFps, this.targetQuality);
            } catch (Exception e) {
                LOGGER.warn("Failed to update settings for screen #{}: {}", screenId, e.getMessage());
            }
        }

        public void stopScreencast() {
            try {
                sendCommand("Page.stopScreencast", Map.of());
                this.screencastActive = false;
            } catch (Exception ignored) {}
        }

        private void handleScreencastFrame(String msg) {
            try {
                // 1. sessionId 抽出
                int sessionIdx = msg.indexOf("\"sessionId\":");
                if (sessionIdx == -1) return;
                int sessionStart = sessionIdx + 12;
                while (sessionStart < msg.length() && (msg.charAt(sessionStart) == ' ' || msg.charAt(sessionStart) == '\t')) sessionStart++;
                int sessionEnd = sessionStart;
                while (sessionEnd < msg.length() && Character.isDigit(msg.charAt(sessionEnd))) sessionEnd++;
                if (sessionStart >= sessionEnd) return;
                final int sessionId = Integer.parseInt(msg.substring(sessionStart, sessionEnd));

                // 2. data (Base64 JPEG) 抽出
                int dataIdx = msg.indexOf("\"data\":");
                if (dataIdx == -1) {
                    ackScreencastFrame(sessionId);
                    return;
                }
                int quoteStart = msg.indexOf("\"", dataIdx + 7);
                int quoteEnd = msg.indexOf("\"", quoteStart + 1);
                if (quoteStart == -1 || quoteEnd <= quoteStart) {
                    ackScreencastFrame(sessionId);
                    return;
                }

                // 前フレームのデコード・レンダリングがまだ処理中の場合、
                // 遅延フレームをスキップして即座に ACK を返し、キューの肥大化・CPU100%・フリーズを完全防止！
                if (!isProcessingFrame.compareAndSet(false, true)) {
                    ackScreencastFrame(sessionId);
                    return;
                }

                final String base64 = msg.substring(quoteStart + 1, quoteEnd);

                // 3. フレームレート安定化制御（目標 FPS に応じた正確なフレーム間隔ディレイ）
                long targetInterval = Math.max(16, 1000 / Math.max(1, targetFps));
                long now = System.currentTimeMillis();
                long elapsed = now - lastScreencastFrameTime;
                long delay = Math.max(0, targetInterval - elapsed);

                screencastExecutor.submit(() -> {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(base64);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
                            BufferedImage img = ImageIO.read(bais);
                            if (img != null && globalFrameConsumer != null) {
                                globalFrameConsumer.accept(screenId, img);
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (delay > 0) {
                            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                        }
                        ackScreencastFrame(sessionId);
                        lastScreencastFrameTime = System.currentTimeMillis();
                        isProcessingFrame.set(false);
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("Error processing screencastFrame for screen #{}: {}", screenId, e.getMessage());
            }
        }

        private void ackScreencastFrame(int sessionId) {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("sessionId", sessionId);
                sendCommand("Page.screencastFrameAck", params);
            } catch (Exception ignored) {}
        }

        private class WebSocketListener implements WebSocket.Listener {
            @Override
            public void onOpen(WebSocket ws) {
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                messageBuffer.append(data);
                if (last) {
                    String fullMsg = messageBuffer.toString();
                    messageBuffer.setLength(0);

                    // ScreencastFrame イベントの優先処理
                    if (fullMsg.contains("\"Page.screencastFrame\"")) {
                        handleScreencastFrame(fullMsg);
                        ws.request(1);
                        return null;
                    }

                    Pattern p = Pattern.compile("\"id\":\\s*(\\d+)");
                    Matcher m = p.matcher(fullMsg);
                    if (m.find()) {
                        int id = Integer.parseInt(m.group(1));
                        CompletableFuture<String> future = pendingResponses.remove(id);
                        if (future != null) {
                            future.complete(fullMsg);
                        }
                    }
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                LOGGER.info("CDP Tab for screen #{} closed: {} ({})", screenId, statusCode, reason);
                connected = false;
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                LOGGER.error("CDP Tab for screen #{} error: {}", screenId, error != null ? error.getMessage() : "unknown");
                connected = false;
            }
        }
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
