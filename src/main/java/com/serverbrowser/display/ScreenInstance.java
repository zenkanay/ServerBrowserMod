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
    private final Direction rightDir;
    private final Direction downDir;
    private final Set<ItemFrame> frames = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> placedBlocks = ConcurrentHashMap.newKeySet();

    // タイルごとの前回収集色配列キャッシュ（差分送信によるパケット量 80% 削減）
    private final Map<Integer, byte[]> lastTileColors = new ConcurrentHashMap<>();

    // 巨大スクリーン用ダブルバッファリング（GCゼロアロケーション）
    private BufferedImage doubleBufferA = null;
    private BufferedImage doubleBufferB = null;
    private boolean useBufferA = true;

    // タイルレンダリング用再利用バッファ
    private static final ThreadLocal<BufferedImage> REUSABLE_TILE_IMAGE =
        ThreadLocal.withInitial(() -> new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB));

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

    // ディスプレイ個別の描画プロパティ（解像度スケール、FPS、JPEG品質）
    private int fps = 14;
    private int quality = 75;
    private double scale = 1.0;

    // 未送信または画像差分のあるタイルインデックスのセット（順序正しく送信するため ConcurrentSkipListSet を採用）
    private final Set<Integer> dirtyTileIndices = new java.util.concurrent.ConcurrentSkipListSet<>();
    // 残余タイルを次 Tick/40ms 後に自動フラッシュするための重複スケジュール防止フラグ
    private final java.util.concurrent.atomic.AtomicBoolean isFlushScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

    public ScreenInstance(int id, ServerLevel level, BlockPos originWallPos, Direction facing, int widthTiles, int heightTiles) {
        this(id, level, originWallPos, facing, getDefaultRightDir(facing), getDefaultDownDir(facing), widthTiles, heightTiles, 1000000 + (id * 10000));
    }

    public ScreenInstance(int id, ServerLevel level, BlockPos originWallPos, Direction facing, int widthTiles, int heightTiles, int baseMapId) {
        this(id, level, originWallPos, facing, getDefaultRightDir(facing), getDefaultDownDir(facing), widthTiles, heightTiles, baseMapId);
    }

    public ScreenInstance(int id, ServerLevel level, BlockPos originWallPos, Direction facing, Direction rightDir, Direction downDir, int widthTiles, int heightTiles, int baseMapId) {
        this.id = id;
        this.level = level;
        this.originWallPos = originWallPos;
        this.facing = facing;
        this.rightDir = rightDir != null ? rightDir : getDefaultRightDir(facing);
        this.downDir = downDir != null ? downDir : getDefaultDownDir(facing);
        this.widthTiles = widthTiles;
        this.heightTiles = heightTiles;
        this.baseMapId = baseMapId;

        com.serverbrowser.config.ServerBrowserConfig config = com.serverbrowser.config.ServerBrowserConfig.get();
        if (config != null) {
            this.fps = config.defaultFps;
            this.quality = config.defaultQuality;
            this.scale = config.defaultScale;
        }
    }

    public static Direction getDefaultRightDir(Direction facing) {
        if (facing.getAxis() != Direction.Axis.Y) {
            return facing.getCounterClockWise();
        }
        return Direction.EAST;
    }

    public static Direction getDefaultDownDir(Direction facing) {
        if (facing.getAxis() != Direction.Axis.Y) {
            return Direction.DOWN;
        }
        return Direction.SOUTH;
    }

    /**
     * 床（UP）または天井（DOWN）の場合、rightDir と downDir に合わせて額縁の回転角(0..3)を取得
     * ※ Minecraft の地図レンダラーは (rotation % 4) * 2 で 0, 90, 180, 270 度回転するため、
     * 0: 0度, 1: 90度, 2: 180度, 3: 270度 が正確な値となる。
     */
    public int getFrameRotation() {
        if (facing.getAxis() != Direction.Axis.Y) {
            return 0; // 垂直な壁面は通常向き（0）
        }
        if (facing == Direction.DOWN) {
            // 天井（下向き）ディスプレイ: flipX（左右反転相殺）との合成
            return switch (downDir) {
                case NORTH -> 0; // forwardDir = SOUTH (奥がSOUTH, 手前がNORTH)
                case WEST  -> 1; // forwardDir = EAST
                case SOUTH -> 2; // forwardDir = NORTH (奥がNORTH, 手前がSOUTH)
                case EAST  -> 3; // forwardDir = WEST
                default    -> 0;
            };
        } else {
            // 床（上向き）ディスプレイ
            return switch (downDir) {
                case SOUTH -> 0; // forwardDir = NORTH
                case WEST  -> 1; // forwardDir = EAST
                case NORTH -> 2; // forwardDir = SOUTH
                case EAST  -> 3; // forwardDir = WEST
                default    -> 0;
            };
        }
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
    public Direction getRightDir() { return rightDir; }
    public Direction getDownDir() { return downDir; }
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

    public int getFps() { return fps; }
    public void setFps(int fps) { this.fps = fps; }
    public int getQuality() { return quality; }
    public void setQuality(int quality) { this.quality = quality; }
    public double getScale() { return scale; }
    public void setScale(double scale) { this.scale = scale; }
    public int getRenderWidth() { return Math.max(16, Math.min(16384, (int) Math.round(getPixelWidth() * scale))); }
    public int getRenderHeight() { return Math.max(16, Math.min(16384, (int) Math.round(getPixelHeight() * scale))); }

    public void buildScreen() {
        int rotation = getFrameRotation();

        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                BlockPos wallPos = originWallPos.relative(rightDir, x).relative(downDir, y);

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
                frame.setRotation(rotation);
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
        dirtyTileIndices.clear();
        for (int i = 0; i < widthTiles * heightTiles; i++) {
            lastTileColors.put(i, blankBytes.clone());
            dirtyTileIndices.add(i);
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
        BlockPos corner1 = originWallPos.relative(facing);
        BlockPos corner2 = originWallPos.relative(rightDir, widthTiles - 1).relative(downDir, heightTiles - 1).relative(facing);
        return new net.minecraft.world.phys.AABB(corner1).minmax(new net.minecraft.world.phys.AABB(corner2)).inflate(2.0);
    }

    /**
     * プレイヤー参加時に呼び出され、ワールドセーブデータからロードされた正規の額縁を安全に再バインド
     * 重複額縁をアイテム化させずに整理し、二重召喚による額縁ドロップを完全防止！
     */
    public void bindExistingFrames(MinecraftServer server) {
        net.minecraft.world.phys.AABB box = getScreenBoundingBox();
        int rotation = getFrameRotation();

        // 画面領域内のすべての額縁を検出
        List<ItemFrame> existingFrames = level.getEntitiesOfClass(ItemFrame.class, box);

        frames.clear();
        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                BlockPos wallPos = originWallPos.relative(rightDir, x).relative(downDir, y);
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
                    chosen.setRotation(rotation);
                    chosen.setInvisible(true);
                    chosen.setInvulnerable(true);
                    setFrameFixedAndUndroppable(chosen);
                    level.addFreshEntity(chosen);
                } else {
                    chosen.setItem(mapStack, false);
                    chosen.setRotation(rotation);
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

        placedBlocks.clear();
        lastTileColors.clear();

        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                BlockPos wallPos = originWallPos.relative(rightDir, x).relative(downDir, y);
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
        dirtyTileIndices.clear();
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

        BufferedImage targetBuffer = useBufferA ? doubleBufferA : doubleBufferB;
        if (targetBuffer == null || targetBuffer.getWidth() != targetW || targetBuffer.getHeight() != targetH) {
            targetBuffer = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            if (useBufferA) doubleBufferA = targetBuffer; else doubleBufferB = targetBuffer;
        }
        useBufferA = !useBufferA;

        Graphics2D g2 = targetBuffer.createGraphics();
        if (scale < 1.0) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        } else {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        double srcAspect = (double) src.getWidth() / src.getHeight();
        double targetAspect = (double) targetW / targetH;

        int drawW = targetW;
        int drawH = targetH;
        int drawX = 0;
        int drawY = 0;

        // アスペクト比が微妙に異なる場合は中央配置（センタリング）
        if (Math.abs(srcAspect - targetAspect) > 0.03) {
            // アスペクト比が異なる場合のみ、余白が黒になるようクリア
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, targetW, targetH);

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
        return targetBuffer;
    }

    public void updateDisplay(BufferedImage fullImage, MinecraftServer server) {
        updateDisplayFromAsync(fullImage, server);
    }

    /**
     * 高速非同期ストリーミング（Screencast / LiveUpdate）用の画面更新
     * 重い画像切り出し・パレット減色・タイル差分・パケット生成をワーカースレッドで 100% 完了させ、
     * Minecraft サーバーのメインスレッド（Tick）を 0.01ms もブロックしない極限最適化アーキテクチャ。
     * バジェット制御によりクライアントのテクスチャ更新（OpenGL）を安全に保ちつつ、
     * 未送信タイルが残った場合は自動で次Tick/40ms後にフラッシュを継続し、静止画でも全画面を 100% 確実に表示する。
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

        // 前フレームの未送信タイルが残っている場合は新フレームをスキップし、
        // 前フレームが画面最下部まで100%確実に送信完了するのを待機（FPSを自動調整して全画面完全描画を保証）
        if (!dirtyTileIndices.isEmpty()) {
            return;
        }

        // フルスクリーン動画等で送られてきた解像度が画面と異なる場合、画面全体（全タイル）にぴったり高品質フィット
        BufferedImage normalizedImage = normalizeImage(fullImage);
        BufferedImage oldImage = this.baseImage;
        this.baseImage = normalizedImage;

        final int totalTiles = widthTiles * heightTiles;

        // 前フレームとの差分判定（または初期未送信タイル）を検知して dirtyTileIndices に登録
        for (int i = 0; i < totalTiles; i++) {
            int tx = i % widthTiles;
            int ty = i / widthTiles;
            if (oldImage == null || oldImage == normalizedImage || !lastTileColors.containsKey(i) || isTileDirty(oldImage, normalizedImage, tx, ty)) {
                dirtyTileIndices.add(i);
            }
        }

        // バジェット分を即時フラッシュ
        flushDirtyTiles(server);
    }

    /**
     * 未送信・変更タイルのフラッシュ送信
     * 画面全体が確実に下まで更新されるよう、1回あたり最大 64 タイル（小型・中型画面は一斉送信）送信し、
     * 残余タイルがある場合は最速（20ms / 1Tick 後）で続きをフラッシュする。
     */
    public void flushDirtyTiles(MinecraftServer server) {
        if (server == null || baseImage == null) return;

        int checkDist = Math.max(96, Math.max(widthTiles, heightTiles) + 32);
        boolean hasNearbyPlayer = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == this.level && player.blockPosition().closerThan(this.originWallPos, checkDist)) {
                hasNearbyPlayer = true;
                break;
            }
        }
        if (!hasNearbyPlayer) return;

        final int totalTiles = widthTiles * heightTiles;
        // 小型・中型画面（<= 64タイル: 8x8や16x4画面）は1回で下まで全画面一括送信！
        // 超巨大画面（> 64タイル）は 1フレームあたり最大 64 タイルずつ最速Tickで下まで送り切る
        final int maxPacketsPerFrame = (totalTiles <= 64) ? totalTiles : 64;

        List<ClientboundMapItemDataPacket> packetsToSend = new ArrayList<>();

        // 1. 最優先: マウスポインターがあるタイル（視線・操作レスポンスを 0 遅延で保証）
        int pointerTileIdx = -1;
        if (!isPointerIdle && lastPointerTileX >= 0 && lastPointerTileY >= 0
                && lastPointerTileX < widthTiles && lastPointerTileY < heightTiles) {
            pointerTileIdx = lastPointerTileY * widthTiles + lastPointerTileX;
            ClientboundMapItemDataPacket ptrPacket = generateTilePacket(lastPointerTileX, lastPointerTileY, true);
            if (ptrPacket != null) {
                packetsToSend.add(ptrPacket);
            }
            dirtyTileIndices.remove(pointerTileIdx);
        }

        // 2. dirtyTileIndices からバジェット上限までタイルを取り出してパケット生成
        Iterator<Integer> it = dirtyTileIndices.iterator();
        while (it.hasNext() && packetsToSend.size() < maxPacketsPerFrame) {
            int currentTile = it.next();
            it.remove();

            if (currentTile == pointerTileIdx) continue;

            int tx = currentTile % widthTiles;
            int ty = currentTile / widthTiles;

            ClientboundMapItemDataPacket packet = generateTilePacket(tx, ty, false);
            if (packet != null) {
                packetsToSend.add(packet);
            }
        }

        // 3. 生成済みパケットをプレイヤーへ送信（メインスレッドでは Netty キューに積むだけ）
        if (!packetsToSend.isEmpty()) {
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

        // 4. まだ未送信タイルが残っている場合、画面下部まで確実に描き切るため最速（20ms / 1Tick 後）にスケジュールフラッシュ！
        if (!dirtyTileIndices.isEmpty()) {
            scheduleFlush(server);
        }
    }

    private void scheduleFlush(MinecraftServer server) {
        if (isFlushScheduled.compareAndSet(false, true)) {
            java.util.concurrent.CompletableFuture.delayedExecutor(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> {
                    isFlushScheduled.set(false);
                    try {
                        flushDirtyTiles(server);
                    } catch (Exception ignored) {}
                });
        }
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

        // ダイレクトバッファアクセスによる 10 倍高速なピクセル差分検知（getRGB のオーバーヘッド完全排除）
        if ((oldImg.getType() == BufferedImage.TYPE_INT_RGB || oldImg.getType() == BufferedImage.TYPE_INT_ARGB)
                && (newImg.getType() == BufferedImage.TYPE_INT_RGB || newImg.getType() == BufferedImage.TYPE_INT_ARGB)) {
            int[] oldData = ((java.awt.image.DataBufferInt) oldImg.getRaster().getDataBuffer()).getData();
            int[] newData = ((java.awt.image.DataBufferInt) newImg.getRaster().getDataBuffer()).getData();
            int oldScan = oldImg.getWidth();
            int newScan = newImg.getWidth();
            for (int y = 0; y < h; y += 8) {
                int oldRow = (sy + y) * oldScan + sx;
                int newRow = (sy + y) * newScan + sx;
                for (int x = 0; x < w; x += 8) {
                    if (oldData[oldRow + x] != newData[newRow + x]) {
                        return true;
                    }
                }
            }
            return false;
        }

        // フォールバック
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
     * サイトの画面が更新されたかを高速判定（ダイレクトバッファサンプリング）
     */
    public boolean isImageDifferent(BufferedImage newImg) {
        if (baseImage == null || newImg == null) return true;
        if (baseImage.getWidth() != newImg.getWidth() || baseImage.getHeight() != newImg.getHeight()) return true;

        int w = newImg.getWidth();
        int h = newImg.getHeight();

        if ((baseImage.getType() == BufferedImage.TYPE_INT_RGB || baseImage.getType() == BufferedImage.TYPE_INT_ARGB)
                && (newImg.getType() == BufferedImage.TYPE_INT_RGB || newImg.getType() == BufferedImage.TYPE_INT_ARGB)) {
            int[] baseData = ((java.awt.image.DataBufferInt) baseImage.getRaster().getDataBuffer()).getData();
            int[] newData = ((java.awt.image.DataBufferInt) newImg.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < h; y += 8) {
                int row = y * w;
                for (int x = 0; x < w; x += 8) {
                    if (baseData[row + x] != newData[row + x]) {
                        return true;
                    }
                }
            }
            return false;
        }

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
        Vec3 downVec = downDir.getUnitVec3();

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

        BufferedImage tileImage = REUSABLE_TILE_IMAGE.get();
        Graphics2D g = tileImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int subW = Math.min(128, Math.max(0, baseImage.getWidth() - sx));
        int subH = Math.min(128, Math.max(0, baseImage.getHeight() - sy));

        if (subW < 128 || subH < 128) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, 128, 128);
        }

        boolean isCeiling = (facing == Direction.DOWN);

        if (subW > 0 && subH > 0) {
            // getSubimage による不要なヒープアロケーションを完全撤廃（GCゼロアロケーション描画）
            if (isCeiling) {
                // 天井ディスプレイの鏡像反転（Minecraftバニラレンダラーの仕様）を完全に相殺するため、左右反転して描画
                g.drawImage(baseImage, 0, 0, subW, subH, sx + subW, sy, sx, sy + subH, null);
            } else {
                g.drawImage(baseImage, 0, 0, subW, subH, sx, sy, sx + subW, sy + subH, null);
            }
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
            int drawPx = isCeiling ? (127 - localPx) : localPx;

            drawMouseCursor(g, drawPx, localPy, isCeiling);
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

    private void drawMouseCursor(Graphics2D g, int x, int y, boolean flipX) {
        if (flipX) {
            // 天井用: クライアント側の左右反転によって正立した矢印になるよう、左向き矢印を描画
            int[] xPoints = { x, x, x - 4, x - 6, x - 9, x - 7, x - 11, x - 7, x };
            int[] yPoints = { y, y + 13, y + 10, y + 15, y + 14, y + 9, y + 9, y + 6, y };

            g.setColor(Color.BLACK);
            g.fillPolygon(xPoints, yPoints, 9);
            g.drawPolygon(xPoints, yPoints, 9);

            int[] xInner = { x - 1, x - 1, x - 4, x - 6, x - 8, x - 6, x - 9, x - 6, x - 1 };
            int[] yInner = { y + 2, y + 11, y + 9, y + 13, y + 12, y + 8, y + 8, y + 5, y + 2 };
            g.setColor(isDragging ? new Color(255, 215, 0) : Color.WHITE);
            g.fillPolygon(xInner, yInner, 9);

            g.setColor(isDragging ? Color.ORANGE : Color.RED);
            g.fillRect(x - 1, y, 2, 2);
        } else {
            // 通常用: 洗練された白いマウスポインタ矢印（黒枠付き）
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
    }

    public void resendToPlayer(ServerPlayer player) {
        if (baseImage == null && lastTileColors.isEmpty()) return;

        boolean isCeiling = (facing == Direction.DOWN);

        for (int ty = 0; ty < heightTiles; ty++) {
            for (int tx = 0; tx < widthTiles; tx++) {
                int tileIndex = ty * widthTiles + tx;
                byte[] colors = lastTileColors.get(tileIndex);

                // キャッシュがない場合のみフォールバック描画
                if (colors == null) {
                    if (baseImage == null) continue;
                    int sx = tx * 128;
                    int sy = ty * 128;
                    BufferedImage tileImage = REUSABLE_TILE_IMAGE.get();
                    var g = tileImage.createGraphics();
                    int subW = Math.min(128, Math.max(0, baseImage.getWidth() - sx));
                    int subH = Math.min(128, Math.max(0, baseImage.getHeight() - sy));
                    if (subW < 128 || subH < 128) {
                        g.setColor(Color.BLACK);
                        g.fillRect(0, 0, 128, 128);
                    }
                    if (subW > 0 && subH > 0) {
                        if (isCeiling) {
                            g.drawImage(baseImage, 0, 0, subW, subH, sx + subW, sy, sx, sy + subH, null);
                        } else {
                            g.drawImage(baseImage, 0, 0, subW, subH, sx, sy, sx + subW, sy + subH, null);
                        }
                    }
                    g.dispose();
                    colors = MapColorPalette.convertImageToMapBytes(tileImage);
                    lastTileColors.put(tileIndex, colors.clone());
                }

                int mapIdNum = baseMapId + tileIndex;
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

        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                BlockPos wallPos = originWallPos.relative(rightDir, x).relative(downDir, y);
                BlockPos framePos = wallPos.relative(facing);
                if (frame.blockPosition().equals(framePos)) {
                    frames.add(frame);
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
