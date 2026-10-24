package com.serverbrowser.engine;

import java.awt.image.BufferedImage;

public interface IBrowserEngine {
    /**
     * 指定されたURLまたは検索クエリをレンダリングして BufferedImage を返す
     */
    BufferedImage renderPage(String target, int width, int height, int scrollY) throws Exception;

    String getEngineName();

    boolean isAvailable();
}
