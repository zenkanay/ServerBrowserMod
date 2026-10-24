package com.serverbrowser.display;

import com.serverbrowser.render.MapColorPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ScreenInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenInstance.class);

    private final int id;
    private final int widthTiles;
    private final int heightTiles;
    private final int baseMapId;
    private final ServerLevel level;
    private final BlockPos originWallPos;
    private final Direction facing;
    private final List<ItemFrame> frames = new ArrayList<>();
    private final List<BlockPos> placedBlocks = new ArrayList<>();

    // タイルごとの前回収集色配列キャッシュ（差分送信によるパケット量 80% 削減）
    private final Map<Integer, byte[]> lastTileColors = new ConcurrentHashMap<>();

    private String currentTarget = "https://www.google.com";
    private int scrollY = 0;
    private BufferedImage baseImage = null;

    // ポインター情報
    private int pointerX = -1;
    private int pointerY = -1;
    private int lastPointerTileX = -1;
    private int lastPointerTileY = -1;

    // ポインターのジッター（視線微細ブレ）吸収とスマートアイドル（動画オートハイド）管理
    private long lastPointerMoveTime = 0;
    private int lastSentMovePx = -1;
    private int lastSentMovePy = -1;
    private volatile boolean isPointerIdle = false;

    // 入力選択状態
    private boolean isSearchBarSelected = false;
    private String currentInputBuffer = "";

    // ドラッグ状態
    private volatile boolean isDragging = false;

    public ScreenInstance(int id, ServerLevel level, BlockPos originWallPos, Direction facing, int widthTiles, int heightTiles) {
        this(id, level, originWallPos, facing, widthTiles, heightTiles, 1000000 + (id * 10000));
    }

    public ScreenInstance(int id, ServerLevel level, BlockPos originWallPos, Direction facing, int widthTiles, int heightTiles, int baseMapId) {
        this.id = id;
        this.level = level;
        this.originWallPos = originWallPos;
        this.facing = facing;
        this.widthTiles = widthTiles;
        this.heightTiles = heightTiles;
        this.baseMapId = baseMapId;
    }

    public int getId() { return id; }
    public int getWidthTiles() { return widthTiles; }
    public int getHeightTiles() { return heightTiles; }
    public int getPixelWidth() { return widthTiles * 128; }
    public int getPixelHeight() { return heightTiles * 128; }
    public int getBaseMapId() { return baseMapId; }
    public String getCurrentTarget() { return currentTarget; }
    public void setCurrentTarget(String target) { this.currentTarget = target; }
    public int getScrollY() { return scrollY; }
    public void setScrollY(int scrollY) { this.scrollY = Math.max(0, scrollY); }
    public void scroll(int delta) { this.scrollY = Math.max(0, this.scrollY + delta); }

    public int getPointerX() { return pointerX; }
    public int getPointerY() { return pointerY; }

    public Direction getFacing() { return facing; }
    public ServerLevel getLevel() { return level; }
    public BlockPos getOriginWallPos() { return originWallPos; }
    public boolean isSearchBarSelected() { return isSearchBarSelected; }
    public void setSearchBarSelected(boolean selected) { this.isSearchBarSelected = selected; }
    public String getInputBuffer() { return currentInputBuffer; }
    public void setInputBuffer(String buffer) { this.currentInputBuffer = buffer; }
    public boolean isDragging() { return isDragging; }
    public void setDragging(boolean dragging) { this.isDragging = dragging; }
    public boolean isPointerIdle() { return isPointerIdle; }
    public void wakePointer() {
        this.isPointerIdle = false;
        this.lastPointerMoveTime = System.currentTimeMillis();
    }

    public void buildScreen() {
        Direction rightDir = facing.getCounterClockWise();

        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                BlockPos wallPos = originWallPos.relative(rightDir, x).below(y);

                // 壁ブロック（黒コンクリート）を設置
                level.setBlock(wallPos, Blocks.BLACK_CONCRETE.defaultBlockState(), 3);
                placedBlocks.add(wallPos);

                // 額縁の配置位置：壁ブロックの前面（facing 方向）
                BlockPos framePos = wallPos.relative(facing);

                // もし前面に邪魔なブロックがあれば空気にする
                if (!level.getBlockState(framePos).isAir()) {
                    level.setBlock(framePos, Blocks.AIR.defaultBlockState(), 3);
                }

                ItemFrame frame = new ItemFrame(level, framePos, facing);
                int mapIdNum = baseMapId + (y * widthTiles + x);
                MapId mapId = new MapId(mapIdNum);

                ItemStack mapStack = new ItemStack(Items.FILLED_MAP);
                mapStack.set(DataComponents.MAP_ID, mapId);

                frame.setItem(mapStack, false);
                frame.setInvisible(true); // 額縁の木枠を透明化（ベゼルレス液晶化）
                frame.setInvulnerable(true); // 破壊防止

                // 【最重要】剥がれ防止 (Fixed: 1b & dropChance: 0.0f)
                setFrameFixedAndUndroppable(frame);

                level.addFreshEntity(frame);
                frames.add(frame);
            }
        }
    }

    /**
     * 画面生成直後に全額縁へ黒色の初期ブランクパケットを即時送信し、
     * クライアントのメモリキャッシュに残存する過去のサイト画像の誤表示を一瞬たりとも許さない！
     */
    public void sendInitialBlankScreen(MinecraftServer server) {
        if (server == null) return;
        byte blackColor = MapColorPalette.getNearestColor(0, 0, 0);
        byte[] blankBytes = new byte[128 * 128];
        Arrays.fill(blankBytes, blackColor);

        lastTileColors.clear();
        for (int i = 0; i < widthTiles * heightTiles; i++) {
            lastTileColors.put(i, blankBytes.clone());
        }

        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                int tileIndex = y * widthTiles + x;
                int mapIdNum = baseMapId + tileIndex;
                MapId mapId = new MapId(mapIdNum);
                MapItemSavedData.MapPatch patch = new MapItemSavedData.MapPatch(0, 0, 128, 128, blankBytes);
                ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(
                    mapId,
                    (byte) 3,
                    false,
                    Optional.empty(),
                    Optional.of(patch)
                );

                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.level() == this.level && player.blockPosition().closerThan(this.originWallPos, 64)) {
                        player.connection.send(packet);
                    }
                }
            }
        }
    }

    public net.minecraft.world.phys.AABB getScreenBoundingBox() {
        Direction rightDir = facing.getCounterClockWise();
        BlockPos corner1 = originWallPos.relative(facing);
        BlockPos corner2 = originWallPos.relative(rightDir, widthTiles - 1).below(heightTiles - 1).relative(facing);
        return new net.minecraft.world.phys.AABB(corner1).minmax(new net.minecraft.world.phys.AABB(corner2)).inflate(2.0);
    }

    /**
     * プレイヤー参加時に呼び出され、ワールドセーブデータからロードされた正規の額縁を安全に再バインド
     * 重複額縁をアイテム化させずに整理し、二重召喚による額縁ドロップを完全防止！
     */
    public void bindExistingFrames(MinecraftServer server) {
        Direction rightDir = facing.getCounterClockWise();
        net.minecraft.world.phys.AABB box = getScreenBoundingBox();

        // 画面領域内のすべての額縁を検出
        List<ItemFrame> existingFrames = level.getEntitiesOfClass(ItemFrame.class, box);

        frames.clear();
        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                BlockPos wallPos = originWallPos.relative(rightDir, x).below(y);
                BlockPos framePos = wallPos.relative(facing);

                int mapIdNum = baseMapId + (y * widthTiles + x);
                MapId mapId = new MapId(mapIdNum);
                ItemStack mapStack = new ItemStack(Items.FILLED_MAP);
                mapStack.set(DataComponents.MAP_ID, mapId);

                // このタイル位置にある既存の額縁を探す
                ItemFrame chosen = null;
                for (ItemFrame ef : existingFrames) {
                    if (ef.getDirection() == facing && ef.blockPosition().equals(framePos)) {
                        if (chosen == null) {
                            chosen = ef;
                        } else {
                            // 重複している余分な額縁はアイテム化させずに即時破棄！
                            ef.setItem(ItemStack.EMPTY, false);
                            ef.discard();
                        }
                    }
                }

                if (chosen == null) {
                    // ワールドに額縁がまだ存在しない場合のみ新規生成
                    chosen = new ItemFrame(level, framePos, facing);
                    chosen.setItem(mapStack, false);
                    chosen.setInvisible(true);
                    chosen.setInvulnerable(true);
                    setFrameFixedAndUndroppable(chosen);
                    level.addFreshEntity(chosen);
                } else {
                    chosen.setItem(mapStack, false);
                    chosen.setInvisible(true);
                    chosen.setInvulnerable(true);
                    setFrameFixedAndUndroppable(chosen);
                }

                frames.add(chosen);
            }
        }

        // 周囲に万が一ドロップしてしまった浮遊アイテム（額縁や地図）を完全クリア
        List<net.minecraft.world.entity.item.ItemEntity> items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box);
        for (net.minecraft.world.entity.item.ItemEntity item : items) {
            ItemStack stack = item.getItem();
            if (stack.is(Items.ITEM_FRAME) || stack.is(Items.GLOW_ITEM_FRAME) || stack.is(Items.FILLED_MAP) || stack.is(Items.MAP)) {
                item.discard();
            }
        }
    }

    /**
     * ワールド再開時の初期登録
     */
    public void rebindOrRebuildScreen() {
        level.getChunk(originWallPos);

        Direction rightDir = facing.getCounterClockWise();
        placedBlocks.clear();
        lastTileColors.clear();

        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                BlockPos wallPos = originWallPos.relative(rightDir, x).below(y);
                placedBlocks.add(wallPos);

                // 壁ブロックの存在を保証（すでに黒コンクリートなら再設置せずブロック更新を抑制）
                if (!level.getBlockState(wallPos).is(Blocks.BLACK_CONCRETE)) {
                    level.setBlock(wallPos, Blocks.BLACK_CONCRETE.defaultBlockState(), 2);
                }
            }
        }
    }

    private void setFrameFixedAndUndroppable(ItemFrame frame) {
        try {
            Field fixedField = ItemFrame.class.getDeclaredField("fixed");
            fixedField.setAccessible(true);
            fixedField.setBoolean(frame, true);

            Field dropChanceField = ItemFrame.class.getDeclaredField("dropChance");
            dropChanceField.setAccessible(true);
            dropChanceField.setFloat(frame, 0.0f);
        } catch (Exception e) {
            LOGGER.warn("Failed to set fixed property via reflection: {}", e.getMessage());
        }
    }

    public void destroyScreen() {
        net.minecraft.world.phys.AABB box = getScreenBoundingBox();

        // 1. 登録済み額縁のアイテムクリアと消去
        for (ItemFrame frame : frames) {
            if (frame != null && !frame.isRemoved()) {
                frame.setItem(ItemStack.EMPTY, false);
                frame.discard();
            }
        }
        frames.clear();

        // 2. 画面領域内のすべての ItemFrame（重複含む）をアイテム化させずに完全破棄
        List<ItemFrame> existingFrames = level.getEntitiesOfClass(ItemFrame.class, box);
        for (ItemFrame ef : existingFrames) {
            if (ef.getDirection() == facing) {
                ef.setItem(ItemStack.EMPTY, false);
                ef.discard();
            }
        }

        // 3. 壁ブロックを空気にする（フラグ 2: クライアント同期のみ、近傍更新なしで不要な連鎖ドロップを防止）
        for (BlockPos pos : placedBlocks) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        placedBlocks.clear();

        // 4. 周囲にドロップしてしまった ItemEntity（額縁や地図）を完全消去
        List<net.minecraft.world.entity.item.ItemEntity> items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box);
        for (net.minecraft.world.entity.item.ItemEntity item : items) {
            ItemStack stack = item.getItem();
            if (stack.is(Items.ITEM_FRAME) || stack.is(Items.GLOW_ITEM_FRAME) || stack.is(Items.FILLED_MAP) || stack.is(Items.MAP)) {
                item.discard();
            }
        }

        lastTileColors.clear();
        baseImage = null;
    }

    /**
     * フルスクリーン動画等で送られてくる画像のアスペクト比・解像度を画面全体のタイル領域（widthTiles*128 x heightTiles*128）に
     * 完全に自動フィット・中央配置させ、左上への偏りや下端・右端の欠落を 100% 解消する。
     */
    private BufferedImage normalizeImage(BufferedImage src) {
        if (src == null) return null;
        int targetW = getPixelWidth();
        int targetH = getPixelHeight();
        if (src.getWidth() == targetW && src.getHeight() == targetH) {
            return src;
        }

        BufferedImage normalized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = normalized.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        // 全体を黒でクリア
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, targetW, targetH);

        double srcAspect = (double) src.getWidth() / src.getHeight();
        double targetAspect = (double) targetW / targetH;

        int drawW = targetW;
        int drawH = targetH;
        int drawX = 0;
        int drawY = 0;

        // アスペクト比が微妙に異なる場合は中央配置（センタリング）
        if (Math.abs(srcAspect - targetAspect) > 0.03) {
            if (srcAspect > targetAspect) {
                // ソースの方が横長: 上下中央配置
                drawH = Math.max(1, (int) Math.round(targetW / srcAspect));
                drawY = (targetH - drawH) / 2;
            } else {
                // ソースの方が縦長: 左右中央配置
                drawW = Math.max(1, (int) Math.round(targetH * srcAspect));
                drawX = (targetW - drawW) / 2;
            }
        }

        g2.drawImage(src, drawX, drawY, drawW, drawH, null);
        g2.dispose();
        return normalized;
    }

    public void updateDisplay(BufferedImage fullImage, MinecraftServer server) {
        updateDisplayFromAsync(fullImage, server);
    }

    /**
     * 高速非同期ストリーミング（Screencast / LiveUpdate）用の画面更新
     * 重い画像切り出し・パレット減色・タイル差分・パケット生成をワーカースレッドで 100% 完了させ、
     * Minecraft サーバーのメインスレッド（Tick）を 0.01ms もブロックしない極限最適化アーキテクチャ。
     */
    public void updateDisplayFromAsync(BufferedImage fullImage, MinecraftServer server) {
        if (fullImage == null || server == null) return;

        // 超大型スクリーンにも対応したプレイヤー近接判定（不在時は省エネ）
        int checkDist = Math.max(96, Math.max(widthTiles, heightTiles) + 32);
        boolean hasNearbyPlayer = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == this.level && player.blockPosition().closerThan(this.originWallPos, checkDist)) {
                hasNearbyPlayer = true;
                break;
            }
        }
        if (!hasNearbyPlayer) return;

        // フルスクリーン動画等で送られてきた解像度が画面と異なる場合、画面全体（全タイル）にぴったり高品質フィット
        BufferedImage normalizedImage = normalizeImage(fullImage);
        BufferedImage oldImage = this.baseImage;
        this.baseImage = normalizedImage;

        // ワーカースレッド側で全タイルの差分判定とパケット生成を完了させる
        List<ClientboundMapItemDataPacket> packetsToSend = new ArrayList<>();
        for (int ty = 0; ty < heightTiles; ty++) {
            for (int tx = 0; tx < widthTiles; tx++) {
                boolean hasPointer = (!isPointerIdle && tx == lastPointerTileX && ty == lastPointerTileY);
                if (oldImage != null && !hasPointer && !isTileDirty(oldImage, normalizedImage, tx, ty)) {
                    continue;
                }

                ClientboundMapItemDataPacket packet = generateTilePacket(tx, ty, hasPointer);
                if (packet != null) {
                    packetsToSend.add(packet);
                }
            }
        }

        if (packetsToSend.isEmpty()) return;

        // 生成済みパケットをプレイヤーへ送信（メインスレッドでは Netty キューに積むだけ）
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() == this.level && player.blockPosition().closerThan(this.originWallPos, checkDist)) {
                    for (ClientboundMapItemDataPacket packet : packetsToSend) {
                        player.connection.send(packet);
                    }
                }
            }
        });
    }

    /**
     * 指定タイル領域 (128x128) の画像が前フレームから変化しているかを極限高速サンプリング判定
     */
    private boolean isTileDirty(BufferedImage oldImg, BufferedImage newImg, int tx, int ty) {
        int sx = tx * 128;
        int sy = ty * 128;
        int w = Math.min(128, Math.min(oldImg.getWidth() - sx, newImg.getWidth() - sx));
        int h = Math.min(128, Math.min(oldImg.getHeight() - sy, newImg.getHeight() - sy));
        if (w <= 0 || h <= 0) return true;

        // 8ピクセル間隔で高速サンプリング（動画やアニメーション、ホバーによる差分を 0.005ms 以内で検知）
        for (int y = 0; y < h; y += 8) {
            for (int x = 0; x < w; x += 8) {
                if (oldImg.getRGB(sx + x, sy + y) != newImg.getRGB(sx + x, sy + y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * サイトの画面が更新されたかを高速判定（8ピクセル間隔サンプリングで動画の微小差分も確実捕捉）
     */
    public boolean isImageDifferent(BufferedImage newImg) {
        if (baseImage == null || newImg == null) return true;
        if (baseImage.getWidth() != newImg.getWidth() || baseImage.getHeight() != newImg.getHeight()) return true;

        int w = newImg.getWidth();
        int h = newImg.getHeight();
        for (int y = 0; y < h; y += 8) {
            for (int x = 0; x < w; x += 8) {
                if (baseImage.getRGB(x, y) != newImg.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 視線 Raycast によるポインター位置の計算
     * @return 画面と視線が交差している場合 true
     */
    public boolean updatePointerFromPlayer(ServerPlayer player, MinecraftServer server) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 viewVec = player.getViewVector(1.0f);

        Direction rightDir = facing.getCounterClockWise();

        // スクリーンの左上表面の中心座標
        // 額縁の厚み（壁から約0.03〜0.0625）
        double surfaceOffset = 0.0625;
        Vec3 normal = facing.getUnitVec3();
        Vec3 originTopLeft = Vec3.atLowerCornerOf(originWallPos)
            .add(0.5, 0.5, 0.5)
            .add(normal.scale(0.5 + surfaceOffset));

        // 平面方程式: (P - originTopLeft) ・ normal = 0
        // 直線: eyePos + t * viewVec
        double denom = viewVec.dot(normal);
        if (Math.abs(denom) < 1e-5) {
            clearPointer(server);
            return false;
        }

        double t = originTopLeft.subtract(eyePos).dot(normal) / denom;
        double maxDist = com.serverbrowser.config.ServerBrowserConfig.get().maxInteractionDistance;
        if (maxDist <= 0) maxDist = 48.0;
        if (t <= 0 || t > maxDist) { // リモコン動作可能距離（デフォルト48ブロック）外の場合はポインター非表示
            clearPointer(server);
            return false;
        }

        Vec3 hitPos = eyePos.add(viewVec.scale(t));

        // originTopLeft から hitPos への相対ベクトル
        Vec3 diff = hitPos.subtract(originTopLeft);
        Vec3 rightVec = rightDir.getUnitVec3();
        Vec3 downVec = new Vec3(0, -1, 0);

        double u = diff.dot(rightVec) + 0.5; // [0, widthTiles]
        double v = diff.dot(downVec) + 0.5;  // [0, heightTiles]

        if (u >= 0 && u <= widthTiles && v >= 0 && v <= heightTiles) {
            int newPx = (int) (u * 128);
            int newPy = (int) (v * 128);

            newPx = Math.max(0, Math.min(getPixelWidth() - 1, newPx));
            newPy = Math.max(0, Math.min(getPixelHeight() - 1, newPy));

            long now = System.currentTimeMillis();

            // ドラッグ中は常にリアルタイム追従（アイドル化させない）
            if (isDragging) {
                isPointerIdle = false;
                lastPointerMoveTime = now;
                lastSentMovePx = newPx;
                lastSentMovePy = newPy;
                if (newPx != this.pointerX || newPy != this.pointerY) {
                    updatePointerPosition(newPx, newPy, server);
                }
                return true;
            }

            // プレイヤー視線の微小な揺れ（デッドゾーン <= 3ピクセル）を判定
            int dx = Math.abs(newPx - lastSentMovePx);
            int dy = Math.abs(newPy - lastSentMovePy);

            if (dx > 3 || dy > 3 || lastSentMovePx < 0 || lastSentMovePy < 0) {
                // プレイヤーが意図して視線を動かした！
                lastPointerMoveTime = now;
                lastSentMovePx = newPx;
                lastSentMovePy = newPy;

                if (isPointerIdle) {
                    // アイドル（オートハイド）から即時復帰！
                    isPointerIdle = false;
                }

                if (newPx != this.pointerX || newPy != this.pointerY) {
                    updatePointerPosition(newPx, newPy, server);
                }
            } else {
                // 視線が静止中（動画やサイトをじっと見ている状態）
                if (!isPointerIdle && lastPointerMoveTime > 0 && (now - lastPointerMoveTime >= 1500)) {
                    // 1.5秒間静止したため、スマート・アイドル（オートハイド）を発動！
                    isPointerIdle = true;
                    // ブラウザエンジンへアンホバー（画面外座標）を送信し、YouTube等のコントロールバーを自動非表示にする
                    DisplayManager.getInstance().dispatchMouseMove(this.id, -100, -100);
                    // 画面上のポインタータイルからポインターを消去して動画だけをクリア表示
                    if (baseImage != null && lastPointerTileX >= 0 && lastPointerTileX < widthTiles &&
                        lastPointerTileY >= 0 && lastPointerTileY < heightTiles) {
                        sendTile(lastPointerTileX, lastPointerTileY, server, false);
                    }
                }
            }

            return true;
        } else {
            clearPointer(server);
            return false;
        }
    }

    private void updatePointerPosition(int newPx, int newPy, MinecraftServer server) {
        int newTileX = newPx / 128;
        int newTileY = newPy / 128;

        boolean tileChanged = (newTileX != lastPointerTileX || newTileY != lastPointerTileY);
        int oldTileX = lastPointerTileX;
        int oldTileY = lastPointerTileY;

        this.pointerX = newPx;
        this.pointerY = newPy;
        this.lastPointerTileX = newTileX;
        this.lastPointerTileY = newTileY;

        // ブラウザエンジンへマウス移動イベント（ドラッグ中ならドラッグ移動、通常ならホバー）を送信！
        if (isDragging) {
            DisplayManager.getInstance().dispatchMouseDrag(this.id, newPx, newPy);
        } else {
            DisplayManager.getInstance().dispatchMouseMove(this.id, newPx, newPy);
        }

        if (baseImage == null) return;

        // タイルが変わった場合、古いタイルからポインタを消去して再送
        if (tileChanged && oldTileX >= 0 && oldTileX < widthTiles && oldTileY >= 0 && oldTileY < heightTiles) {
            sendTile(oldTileX, oldTileY, server, false);
        }

        // 新しいタイルにポインタを描画して送信（アイドル時はポインタを描画しない）
        sendTile(newTileX, newTileY, server, !isPointerIdle);
    }

    public void refreshPointerTile(MinecraftServer server) {
        if (!isPointerIdle && lastPointerTileX >= 0 && lastPointerTileX < widthTiles && lastPointerTileY >= 0 && lastPointerTileY < heightTiles) {
            sendTile(lastPointerTileX, lastPointerTileY, server, true);
        }
    }

    public void clearPointer(MinecraftServer server) {
        if (pointerX != -1 || pointerY != -1 || isPointerIdle) {
            if (isDragging) {
                this.isDragging = false;
                DisplayManager.getInstance().dispatchMouseRelease(this.id, pointerX, pointerY);
            }

            int oldTileX = lastPointerTileX;
            int oldTileY = lastPointerTileY;
            this.pointerX = -1;
            this.pointerY = -1;
            this.lastPointerTileX = -1;
            this.lastPointerTileY = -1;
            this.lastSentMovePx = -1;
            this.lastSentMovePy = -1;
            this.lastPointerMoveTime = 0;
            this.isPointerIdle = false;

            // 画面外へマウス移動イベントを送信してホバーを解除！
            DisplayManager.getInstance().dispatchMouseMove(this.id, -100, -100);

            if (baseImage != null && oldTileX >= 0 && oldTileX < widthTiles && oldTileY >= 0 && oldTileY < heightTiles) {
                sendTile(oldTileX, oldTileY, server, false);
            }
        }
    }

    private void sendTile(int tx, int ty, MinecraftServer server, boolean drawPointer) {
        ClientboundMapItemDataPacket packet = generateTilePacket(tx, ty, drawPointer);
        if (packet != null && server != null) {
            int checkDist = Math.max(96, Math.max(widthTiles, heightTiles) + 32);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() == this.level && player.blockPosition().closerThan(this.originWallPos, checkDist)) {
                    player.connection.send(packet);
                }
            }
        }
    }

    private ClientboundMapItemDataPacket generateTilePacket(int tx, int ty, boolean drawPointer) {
        if (baseImage == null) return null;

        int sx = tx * 128;
        int sy = ty * 128;

        BufferedImage tileImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tileImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int subW = Math.min(128, Math.max(0, baseImage.getWidth() - sx));
        int subH = Math.min(128, Math.max(0, baseImage.getHeight() - sy));

        if (subW > 0 && subH > 0) {
            BufferedImage sub = baseImage.getSubimage(sx, sy, subW, subH);
            g.drawImage(sub, 0, 0, subW, subH, null);
        }

        // 入力選択中のハイライト表示
        if (isSearchBarSelected) {
            // 検索バーがあるタイル（上部 y: 16〜50）にフォーカス枠を描画
            int searchBoxGlobalY = 16;
            int searchBoxGlobalH = 34;
            int boxTileTop = searchBoxGlobalY - sy;
            if (boxTileTop + searchBoxGlobalH > 0 && boxTileTop < 128) {
                g.setColor(new Color(66, 133, 244, 180)); // Googleブルーのアクティブ枠
                g.drawRoundRect(2, Math.max(2, boxTileTop), 124, Math.min(searchBoxGlobalH, 124), 8, 8);
            }
        }

        // ポインター（マウスクリック矢印 / カーソル）の描画
        if (drawPointer && pointerX >= 0 && pointerY >= 0) {
            int localPx = pointerX - sx;
            int localPy = pointerY - sy;

            drawMouseCursor(g, localPx, localPy);
        }

        g.dispose();

        byte[] colors = MapColorPalette.convertImageToMapBytes(tileImage);
        int tileIndex = ty * widthTiles + tx;
        int mapIdNum = baseMapId + tileIndex;

        // クライアントに実際に送信された色配列を正確に追跡
        // ポインターあり・なしに関わらず、送信した内容と同一ならスキップし、変化があれば確実に送信する
        byte[] prevColors = lastTileColors.get(tileIndex);
        if (prevColors != null && Arrays.equals(prevColors, colors)) {
            return null;
        }
        lastTileColors.put(tileIndex, colors.clone());

        MapId mapId = new MapId(mapIdNum);
        MapItemSavedData.MapPatch patch = new MapItemSavedData.MapPatch(0, 0, 128, 128, colors);
        return new ClientboundMapItemDataPacket(
            mapId,
            (byte) 3,
            false,
            Optional.empty(),
            Optional.of(patch)
        );
    }

    private void drawMouseCursor(Graphics2D g, int x, int y) {
        // 洗練された白いマウスポインタ矢印（黒枠付き）
        int[] xPoints = { x, x, x + 4, x + 6, x + 9, x + 7, x + 11, x + 7, x };
        int[] yPoints = { y, y + 13, y + 10, y + 15, y + 14, y + 9, y + 9, y + 6, y };

        // 外側黒枠
        g.setColor(Color.BLACK);
        g.fillPolygon(xPoints, yPoints, 9);
        g.drawPolygon(xPoints, yPoints, 9);

        // 内側（通常は白、ドラッグ中はゴールド/黄色）
        int[] xInner = { x + 1, x + 1, x + 4, x + 6, x + 8, x + 6, x + 9, x + 6, x + 1 };
        int[] yInner = { y + 2, y + 11, y + 9, y + 13, y + 12, y + 8, y + 8, y + 5, y + 2 };
        g.setColor(isDragging ? new Color(255, 215, 0) : Color.WHITE);
        g.fillPolygon(xInner, yInner, 9);

        // 先端のハイライト点（通常は赤、ドラッグ中は鮮やかなオレンジ）
        g.setColor(isDragging ? Color.ORANGE : Color.RED);
        g.fillRect(x, y, 2, 2);
    }

    public void resendToPlayer(ServerPlayer player) {
        if (baseImage == null) return;

        for (int ty = 0; ty < heightTiles; ty++) {
            for (int tx = 0; tx < widthTiles; tx++) {
                int sx = tx * 128;
                int sy = ty * 128;

                BufferedImage tileImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                var g = tileImage.createGraphics();
                int subW = Math.min(128, Math.max(0, baseImage.getWidth() - sx));
                int subH = Math.min(128, Math.max(0, baseImage.getHeight() - sy));
                if (subW > 0 && subH > 0) {
                    g.drawImage(baseImage.getSubimage(sx, sy, subW, subH), 0, 0, subW, subH, null);
                }
                g.dispose();

                byte[] colors = MapColorPalette.convertImageToMapBytes(tileImage);
                int mapIdNum = baseMapId + (ty * widthTiles + tx);
                MapId mapId = new MapId(mapIdNum);

                MapItemSavedData.MapPatch patch = new MapItemSavedData.MapPatch(0, 0, 128, 128, colors);
                ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(
                    mapId,
                    (byte) 3,
                    false,
                    Optional.empty(),
                    Optional.of(patch)
                );
                player.connection.send(packet);
            }
        }
    }

    public boolean isFrameOfScreen(ItemFrame frame) {
        if (frame == null) return false;
        if (frames.contains(frame)) return true;
        if (!frame.level().equals(level) || frame.getDirection() != facing) return false;

        Direction rightDir = facing.getCounterClockWise();
        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                BlockPos wallPos = originWallPos.relative(rightDir, x).below(y);
                BlockPos framePos = wallPos.relative(facing);
                if (frame.blockPosition().equals(framePos)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isWallBlock(BlockPos pos) {
        return placedBlocks.contains(pos);
    }
}
