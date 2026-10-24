package com.serverbrowser.display;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.serverbrowser.config.ServerBrowserConfig;
import com.serverbrowser.engine.BuiltinSearchEngine;
import com.serverbrowser.engine.CdpBrowserEngine;
import com.serverbrowser.engine.IBrowserEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DisplayManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DisplayManager.class);
    private static final DisplayManager INSTANCE = new DisplayManager();

    public static final String WAND_MARKER = "§6§lブラウザリモコン";

    // 全ての設置されたディスプレイ（ID -> ScreenInstance）
    private final Map<Integer, ScreenInstance> screens = new ConcurrentHashMap<>();
    private ExecutorService renderExecutor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);
    // 追従リフレッシュの多重実行・タスク重複を防止するデバウンス管理
    private final Map<Integer, ScheduledFuture<?>> followUpTasks = new ConcurrentHashMap<>();

    public ExecutorService getRenderExecutor() {
        if (renderExecutor == null || renderExecutor.isShutdown() || renderExecutor.isTerminated()) {
            renderExecutor = Executors.newFixedThreadPool(4);
        }
        return renderExecutor;
    }

    private final CdpBrowserEngine cdpEngine;
    private final BuiltinSearchEngine builtinEngine;
    private String preferredEngine = "cdp";

    private int tickCounter = 0;

    // チャタリング防止用デバウンス（プレイヤーUUID -> 最終右クリック時刻）
    private final Map<UUID, Long> lastClickTimestamps = new ConcurrentHashMap<>();

    // クリック回数追跡（ダブルクリック判定用: プレイヤーUUID -> 直前クリックカウント）
    private final Map<UUID, Integer> lastClickCounts = new ConcurrentHashMap<>();

    // スクロール連打詰まり防止用デバウンス（プレイヤーUUID -> 最終スクロール時刻）
    private final Map<UUID, Long> lastScrollTimestamps = new ConcurrentHashMap<>();

    // 右クリック直後のスイングパケット誤検知（上スクロール暴発）防止用タイムスタンプ
    private final Map<UUID, Long> lastRightClickTimestamps = new ConcurrentHashMap<>();

    // 右クリック操作の多重実行防止（UseEntityCallback と UseItemCallback の同一入力二重発火を完全排除）
    private final Map<UUID, Long> lastWandActionTimestamps = new ConcurrentHashMap<>();

    // ドラッグトグルの二重実行防止用タイムスタンプ（プレイヤーUUID -> 最終トグル時刻）
    private final Map<UUID, Long> lastDragToggleTimestamps = new ConcurrentHashMap<>();

    // 空振りスイング保留キュー（プレイヤーUUID -> 登録Tick数）
    private final Map<UUID, Integer> pendingSwingTicks = new ConcurrentHashMap<>();

    // 画面の自動ライブ更新実行中フラグ
    private final Set<Integer> activeUpdatingScreens = ConcurrentHashMap.newKeySet();

    private volatile MinecraftServer currentServer;
    private final java.util.concurrent.atomic.AtomicInteger nextMapIdCounter = new java.util.concurrent.atomic.AtomicInteger(100);

    public int allocateBaseMapId() {
        return 2000000 + nextMapIdCounter.getAndIncrement() * 50000;
    }

    private DisplayManager() {
        this.cdpEngine = CdpBrowserEngine.findAndCreate();
        this.builtinEngine = new BuiltinSearchEngine();
        if (this.cdpEngine != null && this.cdpEngine.isAvailable()) {
            this.preferredEngine = "cdp";
            LOGGER.info("Real Chromium Multi-Tab CDP engine is active!");

            // 超高速 CDP Page.startScreencast ストリームのフレームリスナー登録
            this.cdpEngine.setGlobalFrameConsumer((screenId, frame) -> {
                ScreenInstance screen = screens.get(screenId);
                MinecraftServer server = this.currentServer;
                if (screen != null && server != null) {
                    screen.updateDisplayFromAsync(frame, server);
                }
            });
        } else {
            this.preferredEngine = "builtin";
            LOGGER.info("Chromium not found, using Fast Builtin Engine.");
        }
    }

    public static DisplayManager getInstance() {
        return INSTANCE;
    }

    public IBrowserEngine getActiveEngine() {
        if ("builtin".equalsIgnoreCase(preferredEngine) || cdpEngine == null || !cdpEngine.isAvailable()) {
            return builtinEngine;
        }
        return cdpEngine;
    }

    public void setPreferredEngine(String engine) {
        this.preferredEngine = engine.toLowerCase();
    }

    public String getPreferredEngine() {
        return preferredEngine;
    }

    public Map<Integer, ScreenInstance> getAllScreens() {
        return Collections.unmodifiableMap(screens);
    }

    public ScreenInstance getScreen(int id) {
        return screens.get(id);
    }

    public void dispatchMouseMove(int screenId, int x, int y) {
        if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
            cdpEngine.dispatchMouseMove(screenId, x, y);
        }
    }

    public void dispatchMouseDrag(int screenId, int x, int y) {
        if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
            cdpEngine.dispatchMouseDrag(screenId, x, y);
        }
    }

    public void dispatchMousePress(int screenId, int x, int y) {
        if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
            cdpEngine.dispatchMousePress(screenId, x, y);
        }
    }

    public void dispatchMouseRelease(int screenId, int x, int y) {
        if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
            cdpEngine.dispatchMouseRelease(screenId, x, y);
        }
    }

    /**
     * ドラッグ操作のトグル（つかむ / 離す）
     * YouTubeシークバー、音量スライダー、Googleマップ移動、テキスト範囲選択などPC同等のドラッグを実現
     */
    public void toggleDrag(ServerPlayer player, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        long now = System.currentTimeMillis();
        Long lastToggle = lastDragToggleTimestamps.get(player.getUUID());
        if (lastToggle != null && (now - lastToggle < 400)) {
            // 400ms 以内の連続トグル（1回のクリックによる二重実行）を完全に弾く！
            return;
        }
        lastDragToggleTimestamps.put(player.getUUID(), now);

        screen.wakePointer();
        screen.updatePointerFromPlayer(player, server);
        int px = screen.getPointerX();
        int py = screen.getPointerY();
        if (px < 0 || py < 0) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        int screenId = screen.getId();

        if (!screen.isDragging()) {
            // ドラッグ開始（つかむ）
            screen.setDragging(true);
            dispatchMousePress(screenId, px, py);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.9f, 1.4f);
            player.sendSystemMessage(Component.literal("§6[ServerBrowser] ドラッグ開始 §7(視線で移動し、もう一度クリックで離す)"));
        } else {
            // ドラッグ終了（離す / ドロップ）
            screen.setDragging(false);
            dispatchMouseRelease(screenId, px, py);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.PLAYERS, 0.9f, 1.2f);
            player.sendSystemMessage(Component.literal("§a[ServerBrowser] ドロップ (ドラッグ完了)"));
        }

        screen.refreshPointerTile(server);
    }

    private void cleanupScreenResources(int screenId) {
        ScheduledFuture<?> f = followUpTasks.remove(screenId);
        if (f != null && !f.isDone()) {
            f.cancel(false);
        }
        activeUpdatingScreens.remove(screenId);
        if (cdpEngine != null) {
            cdpEngine.closeTab(screenId);
        }
    }

    /**
     * ワールドに存在しない幽霊画面（ブロックも額縁も消滅した画面）を自動検出してクリーンアップ
     */
    public synchronized void cleanupGhostScreens(MinecraftServer server) {
        if (server == null || screens.isEmpty()) return;
        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, ScreenInstance> entry : screens.entrySet()) {
            ScreenInstance s = entry.getValue();
            ServerLevel level = s.getLevel();
            if (level != null) {
                BlockPos wallPos = s.getOriginWallPos();
                // チャンクが未ロード（プレイヤーが遠くにいる）場合は判定をスキップし、大切な画面の誤消去を完全防止！
                if (!level.isLoaded(wallPos)) {
                    continue;
                }
                boolean wallExists = level.getBlockState(wallPos).is(net.minecraft.world.level.block.Blocks.BLACK_CONCRETE);
                boolean framesExist = !level.getEntitiesOfClass(ItemFrame.class, s.getScreenBoundingBox()).isEmpty();
                if (!wallExists && !framesExist) {
                    toRemove.add(entry.getKey());
                }
            }
        }
        for (int id : toRemove) {
            ScreenInstance s = screens.remove(id);
            if (s != null) {
                s.destroyScreen();
                cleanupScreenResources(id);
                LOGGER.info("Cleaned up ghost screen #{}", id);
            }
        }
        if (!toRemove.isEmpty()) {
            saveScreens(server);
        }
    }

    /**
     * 空いている最小の正の整数（1, 2, 3...）を取得
     * 幽霊画面を事前に整理して、常に #1 から確実に採番！
     */
    public synchronized int getNextAvailableId(MinecraftServer server) {
        cleanupGhostScreens(server);
        int id = 1;
        while (screens.containsKey(id)) {
            id++;
        }
        return id;
    }

    /**
     * 新しいディスプレイを作成（ワールド内の空き番号 1..N を自動割り当て）
     * プレイヤーの向いている視線角度（ピッチ角）に応じて、壁（垂直面）、床（上向き）、天井（下向き）を自動判定して設置！
     */
    public ScreenInstance createScreen(ServerPlayer player, int widthTiles, int heightTiles) {
        ServerLevel level = (ServerLevel) player.level();
        cleanupGhostScreens(level.getServer());

        float pitch = player.getXRot(); // -90: 真上, 0: 水平, +90: 真下
        Direction forwardDir = player.getDirection(); // プレイヤーの水平向いている方角 (NORTH, SOUTH, EAST, WEST)
        Direction screenFacing;
        Direction rightDir;
        Direction downDir;
        BlockPos originWallPos;

        BlockPos playerPos = player.blockPosition();

        if (pitch > 40.0f) {
            // 【床面（上向き）ディスプレイ】足元を見下ろしている場合
            // 額縁は床ブロックの上面に上向き（UP）に設置
            screenFacing = Direction.UP;
            rightDir = forwardDir.getClockWise(); // プレイヤーから見て右側が X 軸
            downDir = forwardDir.getOpposite();   // プレイヤーから見て手前側（足元側）が画面の下端（奥側が画面の上端）

            // プレイヤーの立ち位置が角（左端）になるよう、目の前から奥に向かって広がる
            // x=0, y=0（奥・左端）の基盤ブロック座標
            originWallPos = playerPos.below().relative(forwardDir, heightTiles);
        } else if (pitch < -40.0f) {
            // 【天井（下向き）ディスプレイ】天井を見上げている場合
            // 額縁は天井ブロックの下面に下向き（DOWN）に設置
            screenFacing = Direction.DOWN;
            rightDir = forwardDir.getClockWise(); // プレイヤーから見て右側が X 軸
            downDir = forwardDir.getOpposite();   // プレイヤーから見て手前側（頭上側）が画面の下端（奥側が画面の上端）

            // プレイヤーの立ち位置が角（左端）になるよう、目の前から奥に向かって広がる
            // x=0, y=0（奥・左端）の基盤ブロック座標
            originWallPos = playerPos.above(3).relative(forwardDir, heightTiles);
        } else {
            // 【通常の壁面ディスプレイ】水平を向いている場合
            screenFacing = forwardDir.getOpposite();
            rightDir = screenFacing.getCounterClockWise();
            downDir = Direction.DOWN;

            // プレイヤーの立ち位置が角（左端）になり、右側へ広がる
            BlockPos wallBaseCorner = playerPos.relative(forwardDir, 2);
            originWallPos = wallBaseCorner.above(heightTiles - 1);
        }

        int screenId = getNextAvailableId(level.getServer());
        int baseMapId = allocateBaseMapId();
        ScreenInstance screen = new ScreenInstance(screenId, level, originWallPos, screenFacing, rightDir, downDir, widthTiles, heightTiles, baseMapId);
        screen.buildScreen();
        screen.sendInitialBlankScreen(level.getServer());
        screens.put(screenId, screen);

        // 画面と1:1で連動する専用リモコン棒を付与
        giveRemoteWand(player, screen);

        // 永続化セーブ
        saveScreens(level.getServer());

        return screen;
    }

    /**
     * プレイヤーがメインハンド（右手）に持っている棒から対応する画面を取得
     * ※ 素手やオフハンド装備による意図しない誤操作を 100% 防止
     */
    public ScreenInstance getHeldScreen(ServerPlayer player) {
        if (player == null) return null;
        return getScreenFromWand(player.getMainHandItem());
    }

    /**
     * ItemStack から対応する画面を取得
     */
    public ScreenInstance getScreenFromWand(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.STICK)) return null;

        // 1. CustomData から画面ID取得
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            Optional<Integer> optId = tag.getInt("browser_screen_id");
            if (optId.isPresent()) {
                return screens.get(optId.get());
            }
        }

        // 2. CustomName から画面ID取得
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            Matcher m = Pattern.compile("(?:画面|Display)\\s*#(\\d+)").matcher(name.getString());
            if (m.find()) {
                int id = Integer.parseInt(m.group(1));
                return screens.get(id);
            }
        }

        return null;
    }

    public boolean isWand(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.STICK)) return false;
        // 1. CustomData による言語非依存の完全判定
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && customData.copyTag().getInt("browser_screen_id").isPresent()) {
            return true;
        }
        // 2. CustomName フォールバック
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            String str = name.getString();
            return str.contains("ブラウザリモコン") || str.contains("Browser Remote Wand") ||
                   str.contains("画面 #") || str.contains("Display #");
        }
        return false;
    }

    public boolean isHoldingRemoteWand(ServerPlayer player) {
        if (player == null) return false;
        return isWand(player.getMainHandItem());
    }

    /**
     * ディスプレイ専用のリモコン棒を付与 (多言語 i18n 完全対応)
     */
    public void giveRemoteWand(ServerPlayer player, ScreenInstance screen) {
        ItemStack wand = new ItemStack(Items.STICK);
        wand.set(DataComponents.CUSTOM_NAME, Component.translatable("serverbrowser.wand.name", screen.getId()));
        wand.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);

        // NBT CustomData に画面IDを記録
        CompoundTag tag = new CompoundTag();
        tag.putInt("browser_screen_id", screen.getId());
        wand.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        List<Component> loreLines = List.of(
            Component.translatable("serverbrowser.wand.lore.screen", screen.getId(), screen.getWidthTiles(), screen.getHeightTiles()),
            Component.translatable("serverbrowser.wand.lore.click"),
            Component.translatable("serverbrowser.wand.lore.drag"),
            Component.translatable("serverbrowser.wand.lore.scroll_up"),
            Component.translatable("serverbrowser.wand.lore.scroll_down"),
            Component.translatable("serverbrowser.wand.lore.destroy"),
            Component.translatable("serverbrowser.wand.lore.note")
        );
        wand.set(DataComponents.LORE, new ItemLore(loreLines));

        // プレイヤーのメインハンド（右手）に新画面のリモコンを直接装備！
        ItemStack currentHeld = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, wand);
        if (!currentHeld.isEmpty()) {
            player.getInventory().add(currentHeld);
        }

        player.sendSystemMessage(Component.translatable("serverbrowser.msg.wand_given"));
        player.sendSystemMessage(Component.translatable("serverbrowser.msg.wand_required"));
    }

    public boolean reacquireWand(ServerPlayer player, int screenId) {
        ScreenInstance screen = screens.get(screenId);
        if (screen == null) {
            player.sendSystemMessage(Component.literal("§c[ServerBrowser] 画面 #" + screenId + " は存在しません。"));
            return false;
        }
        giveRemoteWand(player, screen);
        return true;
    }

    public boolean reacquireWandNearest(ServerPlayer player) {
        ScreenInstance screen = findLookingOrNearestScreen(player, 10.0);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.no_screen_within_range", 10));
            return false;
        }
        giveRemoteWand(player, screen);
        return true;
    }

    /**
     * 手持ちの棒に対応する画面と棒を撤去 (一般プレイヤー用・安全設計)
     */
    public boolean destroyScreenWithWand(ServerPlayer player) {
        ItemStack wandStack = null;
        if (isWand(player.getMainHandItem())) {
            wandStack = player.getMainHandItem();
        } else if (isWand(player.getOffhandItem())) {
            wandStack = player.getOffhandItem();
        }

        if (wandStack == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_to_destroy"));
            return false;
        }

        ScreenInstance screen = getScreenFromWand(wandStack);
        if (screen == null) {
            player.sendSystemMessage(Component.literal("§c[ServerBrowser] 手持ちのリモコン棒に対応する画面が見つかりません。"));
            return false;
        }

        int targetScreenId = screen.getId();
        screens.remove(targetScreenId);
        screen.destroyScreen();
        cleanupScreenResources(targetScreenId);

        // 手に持っている棒を消去
        wandStack.shrink(1);

        // 効果音
        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.ITEM_FRAME_BREAK, SoundSource.PLAYERS, 1.0f, 1.0f);

        player.sendSystemMessage(Component.translatable("serverbrowser.msg.destroyed", targetScreenId));
        saveScreens(level.getServer());
        return true;
    }

    /**
     * プレイヤーの近くにある最寄りのディスプレイを強制撤去 (OP専用)
     */
    public boolean removeNearestScreen(ServerPlayer player, MinecraftServer server) {
        ScreenInstance screen = findLookingOrNearestScreen(player, 16.0);
        if (screen == null) {
            player.sendSystemMessage(Component.literal("§c[ServerBrowser] 16ブロック以内に撤去対象の画面が見つかりません。"));
            return false;
        }

        int targetScreenId = screen.getId();
        screens.remove(targetScreenId);
        screen.destroyScreen();
        cleanupScreenResources(targetScreenId);

        // プレイヤーのインベントリ内に棒があれば消去
        removeWandFromInventory(player, targetScreenId);

        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.ITEM_FRAME_BREAK, SoundSource.PLAYERS, 1.0f, 1.0f);

        player.sendSystemMessage(Component.literal("§a[ServerBrowser] 付近のディスプレイ #" + targetScreenId + " を強制撤去しました！"));
        saveScreens(level.getServer());
        return true;
    }

    public boolean destroyScreenById(int targetScreenId, ServerPlayer player, MinecraftServer server) {
        ScreenInstance screen = screens.remove(targetScreenId);
        if (screen == null) {
            player.sendSystemMessage(Component.literal("§c[ServerBrowser] 画面 #" + targetScreenId + " は存在しません。"));
            return false;
        }

        screen.destroyScreen();
        cleanupScreenResources(targetScreenId);
        removeWandFromInventory(player, targetScreenId);

        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.ITEM_FRAME_BREAK, SoundSource.PLAYERS, 1.0f, 1.0f);

        player.sendSystemMessage(Component.translatable("serverbrowser.msg.destroyed", targetScreenId));
        saveScreens(server);
        return true;
    }

    public boolean resetAllScreens(ServerPlayer player, MinecraftServer server) {
        if (screens.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7[ServerBrowser] 設置されているディスプレイはありません。番号はすでに #1 から開始できます。"));
            return false;
        }

        for (ScreenInstance s : screens.values()) {
            s.destroyScreen();
            cleanupScreenResources(s.getId());
        }
        screens.clear();

        // プレイヤーの全リモコン棒を消去
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isWand(stack)) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }

        saveScreens(server);
        player.sendSystemMessage(Component.literal("§a[ServerBrowser] すべてのディスプレイを完全消去し、番号を #1 にリセットしました！"));
        return true;
    }

    private void removeWandFromInventory(ServerPlayer player, int screenId) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isWand(stack)) {
                ScreenInstance s = getScreenFromWand(stack);
                if (s == null || s.getId() == screenId) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    private ScreenInstance findLookingOrNearestScreen(ServerPlayer player, double maxDist) {
        ScreenInstance closest = null;
        double closestDistSq = maxDist * maxDist;
        for (ScreenInstance s : screens.values()) {
            if (!s.getLevel().equals(player.level())) continue; // 別ディメンションの画面は除外
            double distSq = getDistanceSqToScreen(player, s);
            if (distSq <= closestDistSq) {
                closestDistSq = distSq;
                closest = s;
            }
        }
        return closest;
    }

    /**
     * プレイヤーとディスプレイ画面（全タイル面）との正確な最短距離の2乗を計算
     */
    private double getDistanceSqToScreen(ServerPlayer player, ScreenInstance screen) {
        Direction rightDir = screen.getRightDir();
        Direction downDir = screen.getDownDir();
        double minDistSq = Double.MAX_VALUE;
        for (int y = 0; y < screen.getHeightTiles(); y++) {
            for (int x = 0; x < screen.getWidthTiles(); x++) {
                BlockPos framePos = screen.getOriginWallPos().relative(rightDir, x).relative(downDir, y).relative(screen.getFacing());
                double distSq = player.distanceToSqr(framePos.getX() + 0.5, framePos.getY() + 0.5, framePos.getZ() + 0.5);
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                }
            }
        }
        return minDistSq;
    }

    public void onServerStopping(MinecraftServer server) {
        LOGGER.info("Saving screens and stopping browser engine...");
        saveScreens(server);
        screens.clear(); // シングルプレイでのワールド退出時に必ずメモリをクリアして次回 #1 から開始！
        followUpTasks.values().forEach(f -> f.cancel(false));
        followUpTasks.clear();
        activeUpdatingScreens.clear();
        lastClickTimestamps.clear();
        lastScrollTimestamps.clear();
        if (cdpEngine != null) {
            cdpEngine.stop();
        }
        if (renderExecutor != null && !renderExecutor.isShutdown()) {
            renderExecutor.shutdownNow();
            renderExecutor = null;
        }
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdownNow();
        }
    }

    public static class ScreenSaveData {
        public int id;
        public String dimension;
        public int originX;
        public int originY;
        public int originZ;
        public String facing;
        public String rightDir;
        public String downDir;
        public int widthTiles;
        public int heightTiles;
        public int baseMapId;
        public int fps;
        public int quality;
        public double scale;
        public String url;
        public int scrollY;
    }

    public synchronized void saveScreens(MinecraftServer server) {
        if (server == null) return;
        try {
            Path saveDir = server.getWorldPath(LevelResource.ROOT);
            Path saveFile = saveDir.resolve("serverbrowser_screens.json");

            if (screens.isEmpty()) {
                Files.deleteIfExists(saveFile);
                LOGGER.info("No active screens. Removed save file {}", saveFile);
                return;
            }

            List<ScreenSaveData> list = new ArrayList<>();
            for (ScreenInstance s : screens.values()) {
                // ブラウザ側で遷移した最新の URL を取得して確実に永続化！
                if (cdpEngine != null && cdpEngine.isAvailable()) {
                    try {
                        String liveUrl = cdpEngine.getActiveUrl(s.getId());
                        if (liveUrl != null && !liveUrl.isEmpty()) {
                            s.setCurrentTarget(liveUrl);
                        }
                    } catch (Exception ignored) {}
                }

                ScreenSaveData data = new ScreenSaveData();
                data.id = s.getId();
                data.dimension = s.getLevel().dimension().toString();
                data.originX = s.getOriginWallPos().getX();
                data.originY = s.getOriginWallPos().getY();
                data.originZ = s.getOriginWallPos().getZ();
                data.facing = s.getFacing().getName();
                data.rightDir = s.getRightDir().getName();
                data.downDir = s.getDownDir().getName();
                data.widthTiles = s.getWidthTiles();
                data.heightTiles = s.getHeightTiles();
                data.baseMapId = s.getBaseMapId();
                data.fps = s.getFps();
                data.quality = s.getQuality();
                data.scale = s.getScale();
                data.url = s.getCurrentTarget();
                data.scrollY = s.getScrollY();
                list.add(data);
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String jsonStr = gson.toJson(list);
            Files.writeString(saveFile, jsonStr, StandardCharsets.UTF_8);
            LOGGER.info("Saved {} screens to {}", screens.size(), saveFile);
        } catch (Exception e) {
            LOGGER.error("Failed to save screens: {}", e.getMessage(), e);
        }
    }

    public synchronized void loadScreens(MinecraftServer server) {
        if (server == null) return;
        screens.clear(); // 既存の残存データを完全リセット
        try {
            Path saveDir = server.getWorldPath(LevelResource.ROOT);
            Path saveFile = saveDir.resolve("serverbrowser_screens.json");
            if (!Files.exists(saveFile)) {
                LOGGER.info("No saved screens found at {}", saveFile);
                return;
            }

            String content = Files.readString(saveFile, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            Type listType = new TypeToken<List<ScreenSaveData>>(){}.getType();
            List<ScreenSaveData> list = gson.fromJson(content, listType);

            if (list == null || list.isEmpty()) {
                LOGGER.info("Saved screens file was empty at {}", saveFile);
                return;
            }

            for (ScreenSaveData data : list) {
                if (data.id <= 0 || data.widthTiles <= 0 || data.heightTiles <= 0) continue;

                ServerLevel level = server.overworld();
                for (ServerLevel sl : server.getAllLevels()) {
                    if (sl.dimension().toString().equals(data.dimension) || sl.dimension().toString().contains(data.dimension)) {
                        level = sl;
                        break;
                    }
                }

                Direction facing = Direction.byName(data.facing);
                if (facing == null) facing = Direction.SOUTH;

                Direction rightDir = Direction.byName(data.rightDir);
                Direction downDir = Direction.byName(data.downDir);
                if (rightDir == null) rightDir = ScreenInstance.getDefaultRightDir(facing);
                if (downDir == null) downDir = ScreenInstance.getDefaultDownDir(facing);

                BlockPos origin = new BlockPos(data.originX, data.originY, data.originZ);
                int baseMapId = data.baseMapId > 0 ? data.baseMapId : allocateBaseMapId();
                ScreenInstance screen = new ScreenInstance(data.id, level, origin, facing, rightDir, downDir, data.widthTiles, data.heightTiles, baseMapId);
                screen.setCurrentTarget(data.url != null && !data.url.isEmpty() ? data.url : "https://www.google.com");
                screen.setScrollY(data.scrollY);
                if (data.fps > 0) screen.setFps(data.fps);
                if (data.quality > 0) screen.setQuality(data.quality);
                if (data.scale > 0.0) screen.setScale(data.scale);

                // 額縁の再バインド・再構築
                screen.rebindOrRebuildScreen();
                screens.put(data.id, screen);

                // 初期描画をスケジュール（復元したプロパティ scale, fps, quality を CDP に確実に同期！）
                final ScreenInstance finalScreen = screen;
                getRenderExecutor().submit(() -> {
                    try {
                        BufferedImage img;
                        if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                            img = cdpEngine.renderPageForScreen(finalScreen.getId(), finalScreen.getCurrentTarget(),
                                finalScreen.getPixelWidth(), finalScreen.getPixelHeight(),
                                finalScreen.getQuality(), finalScreen.getFps(), finalScreen.getScale(),
                                finalScreen.getScrollY());
                        } else {
                            img = builtinEngine.renderPage(finalScreen.getCurrentTarget(), finalScreen.getPixelWidth(), finalScreen.getPixelHeight(), finalScreen.getScrollY());
                        }
                        if (img != null) {
                            // 重い減色・差分判定・パケット生成をワーカースレッド内で直接完了（サーバーメインスレッドの Tick ブロック完全ゼロ化）
                            finalScreen.updateDisplayFromAsync(img, server);
                        }
                    } catch (Exception ex) {
                        LOGGER.warn("Failed to load initial page for restored screen #{}: {}", finalScreen.getId(), ex.getMessage());
                    }
                });
            }

            LOGGER.info("Successfully restored {} screens from disk!", screens.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load screens: {}", e.getMessage(), e);
        }
    }

    /**
     * ディスプレイの描画プロパティ（FPS、JPEG品質、解像度スケール）を動的変更し永続化
     */
    public boolean updateScreenProperties(ScreenInstance screen, Integer fps, Integer quality, Double scale, MinecraftServer server) {
        if (screen == null) return false;
        com.serverbrowser.config.ServerBrowserConfig config = com.serverbrowser.config.ServerBrowserConfig.get();

        if (fps != null) {
            int targetFps = Math.max(1, Math.min(config.maxFps, fps));
            screen.setFps(targetFps);
        }
        if (quality != null) {
            int targetQual = Math.max(10, Math.min(config.maxQuality, quality));
            screen.setQuality(targetQual);
        }
        if (scale != null) {
            double targetScale = Math.max(0.01, Math.min(config.maxScale, scale));
            screen.setScale(targetScale);
        }

        if (cdpEngine != null && cdpEngine.isAvailable()) {
            cdpEngine.updateScreenSettings(screen.getId(), screen.getPixelWidth(), screen.getPixelHeight(), screen.getQuality(), screen.getFps(), screen.getScale());
        }

        saveScreens(server);
        return true;
    }

    public void navigate(ServerPlayer player, String target, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }
        navigateScreen(screen, target, player, server);
    }

    public void navigateScreen(ScreenInstance screen, String target, ServerPlayer player, MinecraftServer server) {
        if (screen == null) return;

        // サーバー管理者設定によるブロックドメイン判定
        if (com.serverbrowser.config.ServerBrowserConfig.get().isDomainBlocked(target)) {
            if (player != null) player.sendSystemMessage(Component.translatable("serverbrowser.msg.blocked_domain"));
            return;
        }

        screen.setCurrentTarget(target);
        screen.setScrollY(0);
        screen.setSearchBarSelected(false);

        if (player != null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.navigating", screen.getId(), target));
        }

        int screenId = screen.getId();
        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    // スクリーン専用の独立した Chrome タブでレンダリング（個別プロパティ fps, quality, scale を完全適用）
                    image = cdpEngine.renderPageForScreen(screenId, target,
                        screen.getPixelWidth(), screen.getPixelHeight(),
                        screen.getQuality(), screen.getFps(), screen.getScale(),
                        screen.getScrollY());
                } else {
                    image = builtinEngine.renderPage(target, screen.getPixelWidth(), screen.getPixelHeight(), screen.getScrollY());
                }
            } catch (Exception e) {
                LOGGER.warn("Render failed on screen #{}, falling back to builtin: {}", screenId, e.getMessage());
                try {
                    image = builtinEngine.renderPage(target, screen.getPixelWidth(), screen.getPixelHeight(), screen.getScrollY());
                } catch (Exception ex) {
                    LOGGER.error("Both engines failed: {}", ex.getMessage(), ex);
                }
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server, true);
                if (player != null) {
                    player.sendSystemMessage(Component.translatable("serverbrowser.msg.loaded", screen.getId()));
                }
            } else if (player != null) {
                player.sendSystemMessage(Component.translatable("serverbrowser.msg.load_failed"));
            }

            // ページ遷移直後の遅延ロード・非同期DOM描画を捉える追従リフレッシュ（デバウンス型）
            scheduleFollowUpRefresh(screen, server, 450);
        });
    }

    /**
     * クリック、スクロール、入力、ページ移動後の非同期描画変化を捉えて確実に画面更新する追従スケジューラー
     * 操作連打時のタスク増殖・キュー詰まりをデバウンスで完全防止し、必要な時だけ安全に 1 回リフレッシュ
     */
    public void scheduleFollowUpRefresh(ScreenInstance screen, MinecraftServer server, long delayMs) {
        if (screen == null || server == null || cdpEngine == null || !cdpEngine.isAvailable() || "builtin".equalsIgnoreCase(preferredEngine)) {
            return;
        }
        int screenId = screen.getId();

        // 既存の未実行タスクがあればキャンセル（操作連打時のタスク重複とキュー飽和を完全防止！）
        ScheduledFuture<?> existing = followUpTasks.remove(screenId);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = scheduledExecutor.schedule(() -> {
            followUpTasks.remove(screenId);
            try {
                // Screencast で動画等が現在進行形でフレームを連続受信中なら追従ポーリングは不要
                if (cdpEngine.isReceivingScreencast(screenId)) {
                    return;
                }
                int w = screen.getPixelWidth();
                int h = screen.getPixelHeight();
                BufferedImage latest = cdpEngine.captureScreenshotForScreen(screenId, w, h);
                if (latest != null && screen.isImageDifferent(latest)) {
                    screen.updateDisplayFromAsync(latest, server, false);
                    // 変化があった場合（非同期画像やフォントの遅延ロードに備え）、念押しで800ms後にもう1回だけ追従確認
                    scheduleFollowUpRefresh(screen, server, 800);
                }
            } catch (Exception ignored) {}
        }, delayMs, TimeUnit.MILLISECONDS);

        followUpTasks.put(screenId, future);
    }

    public void scroll(ServerPlayer player, int delta, MinecraftServer server) {
        long now = System.currentTimeMillis();
        Long lastTime = lastScrollTimestamps.get(player.getUUID());
        // 250ms 未満の連打・クリック長押しによる多重暴発を防止（快適・スムーズなスクロール）
        if (lastTime != null && (now - lastTime) < 250) {
            return;
        }
        lastScrollTimestamps.put(player.getUUID(), now);

        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) return;

        screen.scroll(delta);
        int screenId = screen.getId();

        // プレイヤーの視線ポインター位置を取得（YouTube Shortsのコメント欄やモーダル等の内部コンテナを直接スクロール！）
        screen.updatePointerFromPlayer(player, server);
        final int px = screen.getPointerX();
        final int py = screen.getPointerY();

        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    // スクリーン専用のタブで、カーソル位置のコンテナをターゲットにしてディープスクロール
                    image = cdpEngine.performScrollForScreen(screenId, px, py, delta, screen.getPixelWidth(), screen.getPixelHeight());
                } else {
                    image = builtinEngine.renderPage(screen.getCurrentTarget(), screen.getPixelWidth(), screen.getPixelHeight(), screen.getScrollY());
                }
            } catch (Exception e) {
                LOGGER.warn("Scroll failed for screen #{}, fallback to builtin: {}", screenId, e.getMessage());
                try {
                    image = builtinEngine.renderPage(screen.getCurrentTarget(), screen.getPixelWidth(), screen.getPixelHeight(), screen.getScrollY());
                } catch (Exception ignored) {}
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server);
            }
            scheduleFollowUpRefresh(screen, server, 300);
        });
    }

    /**
     * 自然なダブルクリック対応マウスクリック
     * 素早く2回押した場合は「ダブルクリック」、通常のクリックは「シングルクリック」として判定
     */
    public void handleClick(ServerPlayer player, MinecraftServer server) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        Long lastTime = lastClickTimestamps.get(uuid);
        int lastCount = lastClickCounts.getOrDefault(uuid, 0);

        // 1. 同一長押しによる短時間のチャタリング（100ms未満）は無視
        if (lastTime != null && (now - lastTime) < 100) {
            return;
        }

        // 2. ダブルクリック判定（前回のクリックから 100ms 〜 450ms 以内に素早く押された場合）
        int clickCount = 1;
        if (lastTime != null && (now - lastTime) <= 450 && lastCount == 1) {
            clickCount = 2; // ダブルクリック！
        } else {
            clickCount = 1; // 通常のシングルクリック
        }

        lastClickTimestamps.put(uuid, now);
        lastClickCounts.put(uuid, clickCount);

        dispatchClick(player, clickCount, server);
    }

    /**
     * 要件: 「ダブルクリックはコマンドにして」
     * コマンド /browser doubleclick から呼び出されるダブルクリック処理
     */
    public void handleDoubleClick(ServerPlayer player, MinecraftServer server) {
        dispatchClick(player, 2, server);
    }

    /**
     * コマンド /browser rclick から呼び出される右クリック処理
     */
    public void handleRightClick(ServerPlayer player, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        screen.wakePointer();
        screen.updatePointerFromPlayer(player, server);
        int px = screen.getPointerX();
        int py = screen.getPointerY();

        if (px < 0 || py < 0) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, 1.0f);

        int screenId = screen.getId();
        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    image = cdpEngine.performRightClickForScreen(screenId, px, py, screen.getPixelWidth(), screen.getPixelHeight());
                }
            } catch (Exception e) {
                LOGGER.warn("Right click failed for screen #{}: {}", screenId, e.getMessage());
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server);
                if (player != null) {
                    server.execute(() -> {
                        player.sendSystemMessage(Component.literal("§a[ServerBrowser] 右クリックを実行しました。"));
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 350);
        });
    }

    /**
     * コマンド /browser scroll から呼び出されるスクロール処理
     */
    public void scrollByCommand(ServerPlayer player, int delta, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        int actualDelta = delta;
        if (actualDelta == 0 || actualDelta == 1) {
            actualDelta = screen.getAdaptiveScrollAmount();
        } else if (actualDelta == -1) {
            actualDelta = -screen.getAdaptiveScrollAmount();
        }

        final int scrollAmount = actualDelta;
        screen.scroll(scrollAmount);
        int screenId = screen.getId();

        screen.updatePointerFromPlayer(player, server);
        final int px = screen.getPointerX();
        final int py = screen.getPointerY();

        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    image = cdpEngine.performScrollForScreen(screenId, px, py, scrollAmount, screen.getPixelWidth(), screen.getPixelHeight());
                } else {
                    image = builtinEngine.renderPage(screen.getCurrentTarget(), screen.getPixelWidth(), screen.getPixelHeight(), screen.getScrollY());
                }
            } catch (Exception e) {
                LOGGER.warn("Scroll failed for screen #{}: {}", screenId, e.getMessage());
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server);
                if (player != null) {
                    server.execute(() -> {
                        player.sendSystemMessage(Component.literal("§a[ServerBrowser] スクロールしました (" + (scrollAmount > 0 ? "下 " : "上 ") + Math.abs(scrollAmount) + "px)"));
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 300);
        });
    }

    /**
     * コマンド /browser back から呼び出される戻る処理
     */
    public void handleBack(ServerPlayer player, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        int screenId = screen.getId();
        player.sendSystemMessage(Component.literal("§e[ServerBrowser] 前のページに戻っています..."));

        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    image = cdpEngine.performBackForScreen(screenId, screen.getPixelWidth(), screen.getPixelHeight());
                    String latestUrl = cdpEngine.getActiveUrl(screenId);
                    if (latestUrl != null && !latestUrl.isEmpty()) {
                        screen.setCurrentTarget(latestUrl);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Back failed for screen #{}: {}", screenId, e.getMessage());
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server, true);
                if (player != null) {
                    server.execute(() -> {
                        player.sendSystemMessage(Component.literal("§a[ServerBrowser] 前のページに戻りました！ (" + screen.getCurrentTarget() + ")"));
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 450);
        });
    }

    /**
     * コマンド /browser forward から呼び出される進む処理
     */
    public void handleForward(ServerPlayer player, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        int screenId = screen.getId();
        player.sendSystemMessage(Component.literal("§e[ServerBrowser] 次のページに進んでいます..."));

        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    image = cdpEngine.performForwardForScreen(screenId, screen.getPixelWidth(), screen.getPixelHeight());
                    String latestUrl = cdpEngine.getActiveUrl(screenId);
                    if (latestUrl != null && !latestUrl.isEmpty()) {
                        screen.setCurrentTarget(latestUrl);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Forward failed for screen #{}: {}", screenId, e.getMessage());
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server, true);
                if (player != null) {
                    server.execute(() -> {
                        player.sendSystemMessage(Component.literal("§a[ServerBrowser] 次のページに進みました！ (" + screen.getCurrentTarget() + ")"));
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 450);
        });
    }

    /**
     * コマンド /browser reload から呼び出されるページ再読み込み処理
     */
    public void handleReload(ServerPlayer player, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        int screenId = screen.getId();
        player.sendSystemMessage(Component.literal("§e[ServerBrowser] ページを再読み込みしています..."));

        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    image = cdpEngine.performReloadForScreen(screenId, screen.getPixelWidth(), screen.getPixelHeight());
                    String latestUrl = cdpEngine.getActiveUrl(screenId);
                    if (latestUrl != null && !latestUrl.isEmpty()) {
                        screen.setCurrentTarget(latestUrl);
                    }
                } else {
                    image = builtinEngine.renderPage(screen.getCurrentTarget(), screen.getPixelWidth(), screen.getPixelHeight(), screen.getScrollY());
                }
            } catch (Exception e) {
                LOGGER.warn("Reload failed for screen #{}: {}", screenId, e.getMessage());
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server, true);
                if (player != null) {
                    server.execute(() -> {
                        player.sendSystemMessage(Component.literal("§a[ServerBrowser] ページの再読み込みが完了しました！"));
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 450);
        });
    }

    private void dispatchClick(ServerPlayer player, int clickCount, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        screen.wakePointer();
        screen.updatePointerFromPlayer(player, server);
        int px = screen.getPointerX();
        int py = screen.getPointerY();

        if (px < 0 || py < 0) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        final boolean isDouble = (clickCount >= 2);

        // クリック効果音
        ServerLevel level = (ServerLevel) player.level();
        float pitch = isDouble ? 1.6f : 1.2f;
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.8f, pitch);

        int screenId = screen.getId();
        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    image = cdpEngine.performClickForScreen(screenId, px, py, clickCount, screen.getPixelWidth(), screen.getPixelHeight());
                } else {
                    image = fallbackHandleClick(player, screen, px, py, server);
                }
            } catch (Exception e) {
                LOGGER.warn("Click failed for screen #{}, fallback to builtin: {}", screenId, e.getMessage());
                try {
                    image = fallbackHandleClick(player, screen, px, py, server);
                } catch (Exception ignored) {}
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server);
                if (player != null) {
                    server.execute(() -> {
                        if (isDouble) {
                            player.sendSystemMessage(Component.translatable("serverbrowser.msg.double_clicked"));
                        } else {
                            player.sendSystemMessage(Component.translatable("serverbrowser.msg.clicked"));
                        }
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 350);
        });
    }

    private BufferedImage fallbackHandleClick(ServerPlayer player, ScreenInstance screen, int px, int py, MinecraftServer server) throws Exception {
        if (py >= 10 && py <= 55 && px >= 120) {
            screen.setSearchBarSelected(true);
            server.execute(() -> {
                screen.updateDisplay(null, server);
                player.sendSystemMessage(Component.literal("§a[ServerBrowser] 検索バーが選択されました！ /browser type <テキスト> で入力してください。"));
            });
            return null;
        }

        if (px < 100 && py < 50) {
            navigate(player, "https://www.google.com", server);
            return null;
        }

        if (py >= 80) {
            int resultIndex = (py - 80) / 75;
            List<BuiltinSearchEngine.SearchResult> results = builtinEngine.getCachedResults(screen.getCurrentTarget());
            if (resultIndex >= 0 && resultIndex < results.size()) {
                BuiltinSearchEngine.SearchResult item = results.get(resultIndex);
                navigate(player, item.url(), server);
                return null;
            }
        }

        return builtinEngine.renderPage(screen.getCurrentTarget(), screen.getPixelWidth(), screen.getPixelHeight(), screen.getScrollY());
    }

    public void handleTextInput(ServerPlayer player, String text, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        player.sendSystemMessage(Component.translatable("serverbrowser.msg.typing", screen.getId(), text));

        int screenId = screen.getId();
        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    // エンターは勝手に押さず、シンプルに選択中の入力ボックスに文字だけを入力！
                    image = cdpEngine.performTextInputForScreen(screenId, text, false, screen.getPixelWidth(), screen.getPixelHeight());
                } else {
                    server.execute(() -> navigate(player, text, server));
                    return;
                }
            } catch (Exception e) {
                LOGGER.warn("TextInput failed with CDP on screen #{}: {}", screenId, e.getMessage());
                return;
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server);
                if (player != null) {
                    server.execute(() -> {
                        player.sendSystemMessage(Component.translatable("serverbrowser.msg.typed"));
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 350);
        });
    }

    /**
     * Enter キー押下（検索実行やフォーム送信）
     */
    public void handleEnterKey(ServerPlayer player, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        int screenId = screen.getId();
        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    image = cdpEngine.performKeyPressForScreen(screenId, "Enter", 13, screen.getPixelWidth(), screen.getPixelHeight());
                }
            } catch (Exception e) {
                LOGGER.warn("EnterKey failed on screen #{}: {}", screenId, e.getMessage());
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server);
                if (player != null) {
                    server.execute(() -> {
                        player.sendSystemMessage(Component.translatable("serverbrowser.msg.enter_sent"));
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 450);
        });
    }

    /**
     * Backspace キー送信（入力欄の文字を削除）
     */
    public void handleBackspace(ServerPlayer player, int count, MinecraftServer server) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) {
            player.sendSystemMessage(Component.translatable("serverbrowser.msg.hold_wand_required"));
            return;
        }

        int screenId = screen.getId();
        int actualCount = Math.max(1, Math.min(100, count));

        getRenderExecutor().submit(() -> {
            BufferedImage image = null;
            try {
                if (cdpEngine != null && cdpEngine.isAvailable() && !"builtin".equalsIgnoreCase(preferredEngine)) {
                    image = cdpEngine.performBackspaceForScreen(screenId, actualCount, screen.getPixelWidth(), screen.getPixelHeight());
                }
            } catch (Exception e) {
                LOGGER.warn("Backspace failed on screen #{}: {}", screenId, e.getMessage());
            }

            final BufferedImage finalImage = image;
            if (finalImage != null) {
                screen.updateDisplayFromAsync(finalImage, server);
                if (player != null) {
                    server.execute(() -> {
                        player.sendSystemMessage(Component.translatable("serverbrowser.msg.backspace_sent", actualCount));
                    });
                }
            }
            scheduleFollowUpRefresh(screen, server, 300);
        });
    }

    public void queueSwing(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        // 直前 400ms 以内に右クリックが行われていた場合は即時破棄
        if (isRecentRightClick(uuid)) {
            return;
        }
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        if (server != null) {
            pendingSwingTicks.put(uuid, server.getTickCount());
        }
    }

    public void recordRightClick(UUID playerUuid) {
        lastRightClickTimestamps.put(playerUuid, System.currentTimeMillis());
        // 右クリックが行われたら、保留中の空振りスイングを即座に破棄（上スクロール暴発を完全防止！）
        pendingSwingTicks.remove(playerUuid);
    }

    public boolean isRecentRightClick(UUID playerUuid) {
        Long time = lastRightClickTimestamps.get(playerUuid);
        if (time == null) return false;
        return (System.currentTimeMillis() - time) < 400; // 400ms 以内の右クリック直後のスイングパケットは無視
    }

    public boolean handleWandRightClick(ServerPlayer player, ScreenInstance screen) {
        if (player == null || screen == null) return false;

        long now = System.currentTimeMillis();
        Long lastAction = lastWandActionTimestamps.get(player.getUUID());
        if (lastAction != null && (now - lastAction < 250)) {
            // 250ms 以内の同一右クリックパケット重複（UseEntityCallback と UseItemCallback）を完全に排除！
            return true;
        }
        lastWandActionTimestamps.put(player.getUUID(), now);
        recordRightClick(player.getUUID()); // 右クリック時刻を記録してスイングパケット暴発を阻止！

        MinecraftServer server = ((ServerLevel) player.level()).getServer();

        // もしドラッグ中なら右クリックでドロップ（離す）！
        if (screen.isDragging()) {
            toggleDrag(player, server);
            return true;
        }

        if (player.isShiftKeyDown()) {
            toggleDrag(player, server); // Shift + 右クリックは「ドラッグ開始 / 終了」！
        } else {
            handleClick(player, server); // 通常の右クリックは「クリック / ダブルクリック」
        }

        return true;
    }

    public boolean handleWandUse(ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return false;
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) return false;
        return handleWandRightClick(player, screen);
    }

    public boolean handleWandAttack(ServerPlayer player) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) return false;

        MinecraftServer server = ((ServerLevel) player.level()).getServer();

        // もしドラッグ中なら左クリックでも安全にドロップ
        if (screen.isDragging()) {
            toggleDrag(player, server);
            return true;
        }

        // 画面サイズ（縦解像度）に比例した快適な適応スクロール量（画面高さの約20%）
        int scrollDelta = screen.getAdaptiveScrollAmount();
        if (player.isShiftKeyDown()) {
            scroll(player, scrollDelta, server); // Shift + 左クリックは「下スクロール ▼」
        } else {
            scroll(player, -scrollDelta, server); // 左クリックは「上スクロール ▲」
        }
        return true;
    }

    public boolean handleFrameInteraction(ServerPlayer player, ItemFrame frame) {
        ScreenInstance screen = getHeldScreen(player);
        if (screen == null) return false;

        if (screen.isFrameOfScreen(frame)) {
            return handleWandRightClick(player, screen);
        }
        return false;
    }

    public void onServerTick(MinecraftServer server) {
        this.currentServer = server;
        tickCounter++;

        // 1. 保留された空振りスイングの安全な遅延実行（同Tickまたは直後の右クリックを100%除外！）
        if (!pendingSwingTicks.isEmpty()) {
            int currentTick = server.getTickCount();
            for (Map.Entry<UUID, Integer> entry : pendingSwingTicks.entrySet()) {
                UUID uuid = entry.getKey();
                int queuedTick = entry.getValue();

                if (currentTick > queuedTick) {
                    pendingSwingTicks.remove(uuid);
                    if (!isRecentRightClick(uuid)) {
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        if (p != null && isHoldingRemoteWand(p)) {
                            handleWandAttack(p);
                        }
                    }
                }
            }
        }

        // 2. 2 tick に1回: リモコン所持プレイヤーのポインター追従 (他プレイヤーによる消失バグを根絶)
        if (tickCounter % 2 == 0) {
            Set<Integer> activeScreenIds = new HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ScreenInstance heldScreen = getHeldScreen(player);
                if (heldScreen != null) {
                    heldScreen.updatePointerFromPlayer(player, server);
                    activeScreenIds.add(heldScreen.getId());
                }
            }
            // 誰もリモコン棒を持っていない画面のポインターのみをクリア！
            for (ScreenInstance screen : screens.values()) {
                if (!activeScreenIds.contains(screen.getId())) {
                    screen.clearPointer(server);
                }
            }
        }

        // 3. 設定された tick 間隔（デフォルト 2 tick = 約0.1秒 = 10 FPS）でサイト側の画面更新を検知してマイクラ側を自動同期！
        int refreshIntervalMs = ServerBrowserConfig.get().liveRefreshIntervalMs;
        int refreshTicks = Math.max(1, refreshIntervalMs / 50);
        if (tickCounter % refreshTicks == 0) {
            checkLiveScreenUpdates(server);
        }
    }

    /**
     * 要件: 「サイトの画面が更新されたら，マイクラのほうも画面更新するようにして」
     * サイト側での動画再生、非同期ロード、アニメーション等による変化を検知してマイクラ画面を自動同期！
     * Screencast有効時はChromeのゼロ遅延プッシュに委ね、静止時の超重量スクショポーリングを完全停止（CPU 0%）！
     */
    private void checkLiveScreenUpdates(MinecraftServer server) {
        if (cdpEngine == null || !cdpEngine.isAvailable() || "builtin".equalsIgnoreCase(preferredEngine)) {
            return;
        }

        if (screens.isEmpty() || server.getPlayerList().getPlayerCount() == 0) {
            return;
        }

        long now = System.currentTimeMillis();

        for (ScreenInstance screen : screens.values()) {
            int screenId = screen.getId();

            // 超高速 Page.startScreencast が動作している画面は、Chrome の Compositor レベルで
            // 動画・アニメーション・DOM変化を検知して自律的にフレームをプッシュ送信する。
            // そのため、静止画状態の時に重い captureScreenshot を毎秒10回ポーリングするのを完全停止し、小型〜大型全画面で CPU負荷 0% を実現！
            if (cdpEngine.isScreencastActive(screenId)) {
                // Screencastが現在進行形でフレームを連続受信している（動画再生中など）、
                // または直近3秒以内に画面更新があった場合は完全にポーリングをスキップ！
                if (cdpEngine.isReceivingScreencast(screenId) || (now - screen.getLastUpdateTime() < 3000)) {
                    continue;
                }
                // 3秒以上静止しており、かつ近く（16ブロック以内）にプレイヤーがいる時のみ、安全策の超軽量ハートビートとして実行
            }

            if (activeUpdatingScreens.contains(screenId)) {
                continue; // 前回のチェックがまだ実行中ならスキップ
            }

            // 画面の近くにプレイヤーがいるか確認（Screencastアクティブ時は16ブロック、非アクティブ時は32ブロック）
            int checkDist = cdpEngine.isScreencastActive(screenId) ? 16 : 32;
            boolean hasNearbyPlayer = false;
            BlockPos screenPos = screen.getOriginWallPos();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() == screen.getLevel() && player.blockPosition().closerThan(screenPos, checkDist)) {
                    hasNearbyPlayer = true;
                    break;
                }
            }

            if (!hasNearbyPlayer) continue;

            activeUpdatingScreens.add(screenId);
            getRenderExecutor().submit(() -> {
                try {
                    int w = screen.getPixelWidth();
                    int h = screen.getPixelHeight();
                    BufferedImage latest = cdpEngine.captureScreenshotForScreen(screenId, w, h);

                    if (latest != null && screen.isImageDifferent(latest)) {
                        // ワーカースレッド内でタイルの差分判定＆パケット生成を実行（メインスレッド負荷 0%）
                        screen.updateDisplayFromAsync(latest, server);
                    }
                } catch (Exception ignored) {
                } finally {
                    activeUpdatingScreens.remove(screenId);
                }
            });
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = ((ServerLevel) player.level()).getServer();

        if (screens.isEmpty() && server != null) {
            loadScreens(server);
        }

        // プレイヤー参加によりチャンクがアクティブ化されたタイミングで安全に正規の額縁を再バインド
        for (ScreenInstance screen : screens.values()) {
            screen.bindExistingFrames(server);
            screen.resendToPlayer(player);
        }

        // チャンク同期完了後（20 tick / 1秒後）の念押し再バインド＆再送（クライアント側での黒画面防止）
        if (server != null) {
            getRenderExecutor().submit(() -> {
                try {
                    Thread.sleep(1000);
                    server.execute(() -> {
                        for (ScreenInstance screen : screens.values()) {
                            screen.bindExistingFrames(server);
                            screen.resendToPlayer(player);
                        }
                    });
                } catch (Exception ignored) {}
            });
        }
    }
}
