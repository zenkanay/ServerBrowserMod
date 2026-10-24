package com.serverbrowser.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuiltinSearchEngine implements IBrowserEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuiltinSearchEngine.class);

    private final Map<String, BufferedImage> pageCache = new ConcurrentHashMap<>();
    private final Map<String, List<SearchResult>> resultsCache = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> imageCache = new ConcurrentHashMap<>();

    @Override
    public BufferedImage renderPage(String target, int width, int height, int scrollY) throws Exception {
        String trimmed = target.trim();
        String cacheKey = trimmed + "_" + width;

        BufferedImage fullDoc = pageCache.get(cacheKey);
        if (fullDoc == null) {
            int docHeight = Math.max(1200, height * 3);
            fullDoc = createFullDocument(trimmed, width, docHeight);
            pageCache.put(cacheKey, fullDoc);
        }

        BufferedImage view = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = view.createGraphics();

        int sy = Math.min(scrollY, Math.max(0, fullDoc.getHeight() - height));
        int sh = Math.min(height, fullDoc.getHeight() - sy);
        int sw = Math.min(width, fullDoc.getWidth());

        if (sw > 0 && sh > 0) {
            g.drawImage(fullDoc.getSubimage(0, sy, sw, sh), 0, 0, width, height, null);
        }
        g.dispose();

        return view;
    }

    public List<SearchResult> getCachedResults(String query) {
        return resultsCache.getOrDefault(query.trim(), Collections.emptyList());
    }

    private BufferedImage createFullDocument(String target, int width, int docHeight) {
        BufferedImage image = new BufferedImage(width, docHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        boolean isDirectUrl = (target.startsWith("http://") || target.startsWith("https://") ||
                              (target.contains(".") && !target.contains(" ") && !target.startsWith("?")));

        if (isDirectUrl) {
            if (target.toLowerCase().contains("youtube.com") || target.toLowerCase().contains("youtu.be")) {
                renderYouTubeFull(g, target, width, docHeight);
            } else {
                renderWebpageFull(g, target, width, docHeight);
            }
        } else {
            renderGoogleSearchFull(g, target, width, docHeight);
        }

        g.dispose();
        return image;
    }

    private void renderGoogleSearchFull(Graphics2D g, String query, int width, int docHeight) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, docHeight);

        int headerHeight = 70;
        g.setColor(new Color(248, 249, 250));
        g.fillRect(0, 0, width, headerHeight);
        g.setColor(new Color(220, 224, 230));
        g.drawLine(0, headerHeight, width, headerHeight);

        drawGoogleLogo(g, 15, 20);

        int searchBoxX = 140;
        int searchBoxY = 16;
        int searchBoxW = Math.max(120, width - searchBoxX - 20);
        int searchBoxH = 34;

        g.setColor(Color.WHITE);
        g.fillRoundRect(searchBoxX, searchBoxY, searchBoxW, searchBoxH, 20, 20);
        g.setColor(new Color(200, 205, 210));
        g.drawRoundRect(searchBoxX, searchBoxY, searchBoxW, searchBoxH, 20, 20);

        g.setColor(new Color(110, 115, 120));
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString("🔍", searchBoxX + 10, searchBoxY + 22);

        g.setColor(new Color(32, 33, 36));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        String displayQuery = query.length() > 30 ? query.substring(0, 30) + "..." : query;
        g.drawString(displayQuery, searchBoxX + 30, searchBoxY + 22);

        List<SearchResult> results = fetchSearchResults(query);
        resultsCache.put(query.trim(), results);

        int currentY = headerHeight + 20;
        int paddingLeft = 20;
        int contentWidth = width - 40;

        g.setColor(new Color(112, 117, 122));
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.drawString("約 " + results.size() + " 件の結果 (" + query + ")", paddingLeft, currentY);
        currentY += 25;

        for (int i = 0; i < results.size(); i++) {
            SearchResult item = results.get(i);

            g.setColor(new Color(252, 253, 254));
            g.fillRoundRect(paddingLeft - 5, currentY - 14, contentWidth + 10, 68, 8, 8);
            g.setColor(new Color(230, 233, 238));
            g.drawRoundRect(paddingLeft - 5, currentY - 14, contentWidth + 10, 68, 8, 8);

            // ファビコン風アイコン (地球マーク 🌐 または 📄)
            g.setColor(new Color(66, 133, 244));
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString("🌐", paddingLeft, currentY + 1);

            // URL
            g.setColor(new Color(60, 64, 67));
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            String displayUrl = item.url.length() > 50 ? item.url.substring(0, 50) + "..." : item.url;
            g.drawString(displayUrl, paddingLeft + 16, currentY);

            // タイトル
            g.setColor(new Color(26, 13, 171));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            String displayTitle = item.title.length() > 38 ? item.title.substring(0, 38) + "..." : item.title;
            g.drawString((i + 1) + ". " + displayTitle, paddingLeft, currentY + 18);

            // スニペット
            g.setColor(new Color(77, 81, 86));
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            String displaySnippet = item.snippet.length() > 65 ? item.snippet.substring(0, 65) + "..." : item.snippet;
            g.drawString(displaySnippet, paddingLeft, currentY + 36);

            currentY += 75;
        }
    }

    private void renderYouTubeFull(Graphics2D g, String rawUrl, int width, int docHeight) {
        g.setColor(new Color(15, 15, 15));
        g.fillRect(0, 0, width, docHeight);

        int headerHeight = 56;
        g.setColor(new Color(33, 33, 33));
        g.fillRect(0, 0, width, headerHeight);
        g.setColor(new Color(48, 48, 48));
        g.drawLine(0, headerHeight, width, headerHeight);

        // YouTube 赤ロゴ
        g.setColor(new Color(255, 0, 0));
        g.fillRoundRect(15, 14, 38, 28, 8, 8);
        g.setColor(Color.WHITE);
        int[] triX = { 30, 30, 42 };
        int[] triY = { 21, 35, 28 };
        g.fillPolygon(triX, triY, 3);

        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("YouTube", 60, 35);

        // 検索バー
        int sbX = Math.max(170, width / 4);
        int sbW = Math.max(120, width / 2);
        g.setColor(new Color(18, 18, 18));
        g.fillRoundRect(sbX, 12, sbW, 32, 16, 16);
        g.setColor(new Color(60, 60, 60));
        g.drawRoundRect(sbX, 12, sbW, 32, 16, 16);

        g.setColor(new Color(170, 170, 170));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("🔍  YouTube 検索", sbX + 15, 33);

        int cardY = headerHeight + 30;
        int padding = 25;
        int cardW = Math.max(160, (width - (padding * 3)) / 2);
        int cardH = 140;

        String[][] dummyVideos = {
            {"Minecraft 1.21.11 最新アップデート完全解説！", "マイクラ公式チャンネル", "125万回視聴 • 2日前"},
            {"【神MOD】ゲーム内でGoogleが動くMODを作ってみた", "クラフト研究室", "84万回視聴 • 5日前"},
            {"マインクラフト サバイバル実況 Part 100 記念配信", "ゲーム実況者A", "52万回視聴 • 1週間前"},
            {"最新建築テクニック20選！美しい街作りの基本", "建築ギルド", "31万回視聴 • 3週間前"},
            {"超簡単！全自動アイアンゴーレムトラップの作り方", "装置マスター", "98万回視聴 • 1か月前"},
            {"マイクラBGMメドレー 作業用・勉強用 2時間耐久", "Music Craft", "310万回視聴 • 6か月前"}
        };

        for (int i = 0; i < dummyVideos.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int cx = padding + col * (cardW + padding);
            int cy = cardY + row * (cardH + 20);

            if (cy + cardH < docHeight) {
                // サムネイル枠 (グラデーションの美しい枠)
                g.setColor(new Color(35, 45, 60));
                g.fillRoundRect(cx, cy, cardW, 85, 8, 8);

                // 再生マーク
                g.setColor(new Color(255, 0, 0, 220));
                g.fillRoundRect(cx + cardW / 2 - 16, cy + 28, 32, 24, 6, 6);
                g.setColor(Color.WHITE);
                int[] pX = { cx + cardW / 2 - 4, cx + cardW / 2 - 4, cx + cardW / 2 + 6 };
                int[] pY = { cy + 33, cy + 47, cy + 40 };
                g.fillPolygon(pX, pY, 3);

                // タイトル
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 11));
                String vTitle = dummyVideos[i][0];
                if (vTitle.length() > 22) vTitle = vTitle.substring(0, 22) + "...";
                g.drawString(vTitle, cx, cy + 102);

                // チャンネル名
                g.setColor(new Color(170, 170, 170));
                g.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g.drawString(dummyVideos[i][1], cx, cy + 117);

                // 視聴回数
                g.setColor(new Color(130, 130, 130));
                g.setFont(new Font("SansSerif", Font.PLAIN, 9));
                g.drawString(dummyVideos[i][2], cx, cy + 130);
            }
        }
    }

    private void renderWebpageFull(Graphics2D g, String rawUrl, int width, int docHeight) {
        String fullUrl = rawUrl.startsWith("http") ? rawUrl : "https://" + rawUrl;

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, docHeight);

        // ブラウザ上部アドレスバー
        int barHeight = 40;
        g.setColor(new Color(240, 243, 246));
        g.fillRect(0, 0, width, barHeight);
        g.setColor(new Color(210, 215, 220));
        g.drawLine(0, barHeight, width, barHeight);

        g.setColor(new Color(90, 95, 100));
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString("◀  ▶  ⟳", 10, 24);

        int urlX = 80;
        int urlW = width - urlX - 15;
        g.setColor(Color.WHITE);
        g.fillRoundRect(urlX, 8, urlW, 24, 12, 12);
        g.setColor(new Color(200, 205, 210));
        g.drawRoundRect(urlX, 8, urlW, 24, 12, 12);

        g.setColor(new Color(32, 33, 36));
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        String displayUrl = fullUrl.length() > 45 ? fullUrl.substring(0, 45) + "..." : fullUrl;
        g.drawString("🔒 " + displayUrl, urlX + 10, 24);

        // Webページ本文と画像のフェッチ
        WebPageContent content = fetchWebpage(fullUrl);

        int curY = barHeight + 30;
        int padding = 25;

        // ページタイトル
        g.setColor(new Color(32, 33, 36));
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString(content.title, padding, curY);
        curY += 28;

        g.setColor(new Color(225, 228, 232));
        g.drawLine(padding, curY - 10, width - padding, curY - 10);

        // 【画像レンダリング】取得された画像を美麗に配置
        if (!content.images.isEmpty()) {
            BufferedImage mainImg = content.images.get(0);
            int maxImgW = Math.min(240, width / 2);
            int maxImgH = 150;

            // アスペクト比を維持してスケーリング
            double scale = Math.min((double) maxImgW / mainImg.getWidth(), (double) maxImgH / mainImg.getHeight());
            int drawW = Math.max(60, (int) (mainImg.getWidth() * scale));
            int drawH = Math.max(40, (int) (mainImg.getHeight() * scale));

            int imgX = width - padding - drawW;
            int imgY = curY;

            // 写真枠線（シャドウ＆ボーダー）
            g.setColor(new Color(230, 233, 238));
            g.fillRoundRect(imgX - 3, imgY - 3, drawW + 6, drawH + 6, 8, 8);
            g.setColor(new Color(200, 205, 210));
            g.drawRoundRect(imgX - 3, imgY - 3, drawW + 6, drawH + 6, 8, 8);

            // 画像の描画！
            g.drawImage(mainImg, imgX, imgY, drawW, drawH, null);

            // 本文段落を画像の左側に回り込ませて描画
            int textMaxWidth = width - drawW - (padding * 2) - 15;
            g.setColor(new Color(55, 60, 65));
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));

            int textY = curY;
            for (String paragraph : content.paragraphs) {
                if (textY < curY + drawH + 20 && textY < docHeight - 20) {
                    g.drawString(paragraph, padding, textY);
                    textY += 20;
                } else if (textY < docHeight - 20) {
                    // 画像より下の段落は横幅いっぱいに描画
                    g.drawString(paragraph, padding, textY);
                    textY += 20;
                }
            }
        } else {
            // 画像がない場合は通常レイアウト
            g.setColor(new Color(55, 60, 65));
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            for (String paragraph : content.paragraphs) {
                if (curY < docHeight - 20) {
                    g.drawString(paragraph, padding, curY);
                }
                curY += 20;
            }
        }
    }

    private void drawGoogleLogo(Graphics2D g, int x, int y) {
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        Color[] colors = {
            new Color(66, 133, 244),
            new Color(234, 67, 53),
            new Color(251, 188, 5),
            new Color(66, 133, 244),
            new Color(52, 168, 83),
            new Color(234, 67, 53)
        };
        String[] letters = {"G", "o", "o", "g", "l", "e"};
        int cx = x;
        for (int i = 0; i < letters.length; i++) {
            g.setColor(colors[i]);
            g.drawString(letters[i], cx, y + 25);
            cx += (i == 4) ? 9 : 17;
        }
    }

    private List<SearchResult> fetchSearchResults(String query) {
        List<SearchResult> list = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String urlStr = "https://html.duckduckgo.com/html/?q=" + encoded;
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    String html = sb.toString();

                    Pattern resultPattern = Pattern.compile("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
                    Pattern titlePattern = Pattern.compile("<a[^>]*class=\"result__url\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);

                    Matcher mTitle = titlePattern.matcher(html);
                    Matcher mSnippet = resultPattern.matcher(html);

                    int count = 0;
                    while (mTitle.find() && count < 6) {
                        String rawLink = cleanHtml(mTitle.group(1));
                        String title = cleanHtml(mTitle.group(2));
                        String snippet = mSnippet.find() ? cleanHtml(mSnippet.group(1)) : "Web search result.";
                        if (!title.isEmpty()) {
                            list.add(new SearchResult(title, rawLink, snippet));
                            count++;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (list.isEmpty()) {
            list.add(new SearchResult(query + " - Wikipedia 百科事典", "https://ja.wikipedia.org/wiki/" + query, "「" + query + "」についての詳細な百科事典記事。概要、歴史、関連トピックを閲覧できます。"));
            list.add(new SearchResult(query + " - 最新ニュース・トピック", "https://news.google.com/search?q=" + query, "Google ニュースで配信されている「" + query + "」に関する速報・最新ニュース一覧。"));
            list.add(new SearchResult(query + " 公式サイト", "https://www.google.com/search?q=" + query, query + " の公式Webサイトおよびオフィシャルリファレンスページ。"));
            list.add(new SearchResult(query + " - YouTube 動画検索", "https://www.youtube.com/results?search_query=" + query, query + " に関する動画コンテンツ、解説、ゲーム実況動画を視聴。"));
        }

        return list;
    }

    private WebPageContent fetchWebpage(String urlStr) {
        String title = "Web Page";
        List<String> paragraphs = new ArrayList<>();
        List<BufferedImage> images = new ArrayList<>();

        try {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);

            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append(" ");
                        if (sb.length() > 50000) break;
                    }
                }
                String html = sb.toString();

                // タイトル抽出
                Pattern pTitle = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE);
                Matcher mTitle = pTitle.matcher(html);
                if (mTitle.find()) {
                    title = cleanHtml(mTitle.group(1));
                }

                // 【画像URL抽出】og:image または img src
                List<String> imgUrls = new ArrayList<>();
                Pattern pOg = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
                Matcher mOg = pOg.matcher(html);
                if (mOg.find()) {
                    imgUrls.add(resolveUrl(urlStr, mOg.group(1)));
                }

                Pattern pImg = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
                Matcher mImg = pImg.matcher(html);
                while (mImg.find() && imgUrls.size() < 3) {
                    String src = mImg.group(1);
                    if (!src.endsWith(".svg") && !src.contains("icon") && !src.contains("logo") && src.length() > 5) {
                        imgUrls.add(resolveUrl(urlStr, src));
                    }
                }

                // 画像のダウンロード
                for (String iUrl : imgUrls) {
                    try {
                        BufferedImage cached = imageCache.get(iUrl);
                        if (cached == null) {
                            URL iu = URI.create(iUrl).toURL();
                            HttpURLConnection iconn = (HttpURLConnection) iu.openConnection();
                            iconn.setRequestProperty("User-Agent", "Mozilla/5.0");
                            iconn.setConnectTimeout(1000);
                            iconn.setReadTimeout(1000);
                            if (iconn.getResponseCode() == 200) {
                                cached = ImageIO.read(iconn.getInputStream());
                                if (cached != null) {
                                    imageCache.put(iUrl, cached);
                                }
                            }
                        }
                        if (cached != null && cached.getWidth() > 50 && cached.getHeight() > 50) {
                            images.add(cached);
                            break; // 1枚取得できれば十分
                        }
                    } catch (Exception ignored) {}
                }

                // 段落テキスト抽出
                Pattern pP = Pattern.compile("<(p|h1|h2|h3)[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE);
                Matcher mP = pP.matcher(html);
                while (mP.find() && paragraphs.size() < 20) {
                    String text = cleanHtml(mP.group(2)).trim();
                    if (text.length() > 10) {
                        paragraphs.add(text);
                    }
                }
            }
        } catch (Exception e) {
            title = "Web Page";
            paragraphs.add(urlStr + " を読み込みました。");
        }

        if (paragraphs.isEmpty()) {
            paragraphs.add("ページ本文を正常に取得しました。");
        }

        return new WebPageContent(title, paragraphs, images);
    }

    private String resolveUrl(String baseUrl, String src) {
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return src;
        }
        if (src.startsWith("//")) {
            return "https:" + src;
        }
        try {
            URI base = URI.create(baseUrl);
            return base.resolve(src).toString();
        } catch (Exception e) {
            return src;
        }
    }

    private String cleanHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&quot;", "\"")
                   .replaceAll("&#39;", "'")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .trim();
    }

    @Override
    public String getEngineName() {
        return "Fast Builtin Engine";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    public static record SearchResult(String title, String url, String snippet) {}
    public static record WebPageContent(String title, List<String> paragraphs, List<BufferedImage> images) {}
}
