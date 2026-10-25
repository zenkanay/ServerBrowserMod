package com.serverbrowser.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.serverbrowser.config.ServerBrowserConfig;
import com.serverbrowser.display.DisplayManager;
import com.serverbrowser.display.ScreenInstance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;

import java.util.Map;

/**
 * プロフェッショナル仕様の洗練されたブラウザコマンド
 * 多言語 i18n、設定ファイル (config)、権限管理に完全対応
 */
public class BrowserCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("browser")
                .executes(ctx -> showHelp(ctx.getSource(), false))
                .then(Commands.literal("help")
                    .executes(ctx -> showHelp(ctx.getSource(), false))
                    .then(Commands.literal("op")
                        .executes(ctx -> showHelp(ctx.getSource(), true))
                    )
                )
                // 1. 設置: /browser create [横] [縦]
                .then(Commands.literal("create")
                    .executes(ctx -> createScreen(ctx.getSource(), 3, 2))
                    .then(Commands.argument("width", IntegerArgumentType.integer(1, 100))
                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 100))
                            .executes(ctx -> createScreen(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "width"),
                                IntegerArgumentType.getInteger(ctx, "height")
                            ))
                        )
                    )
                )
                // 2. 撤去: /browser destroy (手持ち画面の撤去)
                .then(Commands.literal("destroy")
                    .executes(ctx -> destroyScreen(ctx.getSource()))
                )
                // 3. ホーム（初期サイトへ戻る）: /browser home
                .then(Commands.literal("home")
                    .executes(ctx -> goHome(ctx.getSource()))
                )
                // 4. ページ移動・検索: /browser open <URLまたは検索ワード>
                .then(Commands.literal("open")
                    .then(Commands.argument("url_or_search", StringArgumentType.greedyString())
                        .executes(ctx -> openUrl(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "url_or_search")
                        ))
                    )
                )
                // 5. ページ再読み込み: /browser reload
                .then(Commands.literal("reload")
                    .executes(ctx -> doReload(ctx.getSource()))
                )
                // 6. 戻る / 進む: /browser back, /browser forward
                .then(Commands.literal("back")
                    .executes(ctx -> doBack(ctx.getSource()))
                )
                .then(Commands.literal("forward")
                    .executes(ctx -> doForward(ctx.getSource()))
                )
                // 7. 文字入力: /browser type <テキスト>
                .then(Commands.literal("type")
                    .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> inputType(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "text")
                        ))
                    )
                )
                // 8. 決定: /browser enter
                .then(Commands.literal("enter")
                    .executes(ctx -> pressEnter(ctx.getSource()))
                )
                // 9. 削除: /browser backspace [回数]
                .then(Commands.literal("backspace")
                    .executes(ctx -> pressBackspace(ctx.getSource(), 1))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> pressBackspace(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))
                    )
                )
                // 10. マウス操作: /browser click, /browser rclick, /browser dclick
                .then(Commands.literal("click")
                    .executes(ctx -> doClick(ctx.getSource()))
                )
                .then(Commands.literal("rclick")
                    .executes(ctx -> doRightClick(ctx.getSource()))
                )
                .then(Commands.literal("dclick")
                    .executes(ctx -> doubleClick(ctx.getSource()))
                )
                // 11. ドラッグ操作 (つかむ / 離す): /browser drag
                .then(Commands.literal("drag")
                    .executes(ctx -> doToggleDrag(ctx.getSource()))
                )
                // 12. スクロール操作: /browser scroll [up|down|<delta>] [amount]
                .then(Commands.literal("scroll")
                    .executes(ctx -> doScroll(ctx.getSource(), 0))
                    .then(Commands.literal("up")
                        .executes(ctx -> doScroll(ctx.getSource(), -1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 2000))
                            .executes(ctx -> doScroll(ctx.getSource(), -IntegerArgumentType.getInteger(ctx, "amount")))
                        )
                    )
                    .then(Commands.literal("down")
                        .executes(ctx -> doScroll(ctx.getSource(), 1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 2000))
                            .executes(ctx -> doScroll(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "amount")))
                        )
                    )
                    .then(Commands.argument("delta", IntegerArgumentType.integer(-2000, 2000))
                        .executes(ctx -> doScroll(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "delta")))
                    )
                )
                // 13. リモコン棒再取得: /browser wand
                .then(Commands.literal("wand")
                    .executes(ctx -> getWand(ctx.getSource()))
                )
                // 11. 一覧: /browser list
                .then(Commands.literal("list")
                    .executes(ctx -> listScreens(ctx.getSource()))
                )
                // 12. 画面プロパティ詳細表示: /browser info
                .then(Commands.literal("info")
                    .executes(ctx -> showScreenInfo(ctx.getSource()))
                )
                // 13. FPS 設定: /browser fps <値>
                .then(Commands.literal("fps")
                    .then(Commands.argument("fps", IntegerArgumentType.integer(1, 60))
                        .executes(ctx -> setScreenFps(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "fps")))
                    )
                )
                // 14. 解像度スケール設定: /browser res <倍率>
                .then(Commands.literal("res")
                    .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.01))
                        .executes(ctx -> setScreenScale(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "scale")))
                    )
                )
                // 15. JPEG 画質設定: /browser quality <値>
                .then(Commands.literal("quality")
                    .then(Commands.argument("quality", IntegerArgumentType.integer(10, 100))
                        .executes(ctx -> setScreenQuality(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "quality")))
                    )
                )
                // 16. カーソル倍率設定: /browser cursor <倍率>
                .then(Commands.literal("cursor")
                    .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.1, 10.0))
                        .executes(ctx -> setScreenCursor(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "scale")))
                    )
                )

                // ==== OP専用コマンド ====
                // 16. 付近画面強制撤去: /browser destroy_near (OP専用)
                .then(Commands.literal("destroy_near")
                    .executes(ctx -> destroyNearScreen(ctx.getSource()))
                )
                // 17. ID指定強制撤去: /browser destroy_id <id> (OP専用)
                .then(Commands.literal("destroy_id")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> destroyScreenById(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))
                    )
                )
                // 18. 全画面完全リセット: /browser reset (OP専用)
                .then(Commands.literal("reset")
                    .executes(ctx -> resetScreens(ctx.getSource()))
                )
                // 19. ID指定リモコン棒取得: /browser wand_id <id> (OP専用)
                .then(Commands.literal("wand_id")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> getWandById(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))
                    )
                )
                // 20. ID指定詳細表示: /browser info_id <id> (OP専用)
                .then(Commands.literal("info_id")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> showScreenInfoById(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))
                    )
                )
                // 21. ID指定FPS変更: /browser fps_id <id> <fps> (OP専用)
                .then(Commands.literal("fps_id")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("fps", IntegerArgumentType.integer(1, 60))
                            .executes(ctx -> setScreenFpsById(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"), IntegerArgumentType.getInteger(ctx, "fps")))
                        )
                    )
                )
                // 22. ID指定解像度変更: /browser res_id <id> <scale> (OP専用)
                .then(Commands.literal("res_id")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.01))
                            .executes(ctx -> setScreenScaleById(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"), DoubleArgumentType.getDouble(ctx, "scale")))
                        )
                    )
                )
                // 23. ID指定品質変更: /browser quality_id <id> <quality> (OP専用)
                .then(Commands.literal("quality_id")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("quality", IntegerArgumentType.integer(10, 100))
                            .executes(ctx -> setScreenQualityById(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"), IntegerArgumentType.getInteger(ctx, "quality")))
                        )
                    )
                )
                // 24. ID指定カーソル倍率変更: /browser cursor_id <id> <scale> (OP専用)
                .then(Commands.literal("cursor_id")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.1, 10.0))
                            .executes(ctx -> setScreenCursorById(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"), DoubleArgumentType.getDouble(ctx, "scale")))
                        )
                    )
                )
                // 24. サーバー上限設定: /browser max_fps, max_res, max_quality (OP専用)
                .then(Commands.literal("max_fps")
                    .then(Commands.argument("fps", IntegerArgumentType.integer(1, 60))
                        .executes(ctx -> setServerMaxFps(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "fps")))
                    )
                )
                .then(Commands.literal("max_res")
                    .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.01))
                        .executes(ctx -> setServerMaxScale(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "scale")))
                    )
                )
                .then(Commands.literal("max_quality")
                    .then(Commands.argument("quality", IntegerArgumentType.integer(10, 100))
                        .executes(ctx -> setServerMaxQuality(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "quality")))
                    )
                )
        );
    }

    private static int showHelp(CommandSourceStack source, boolean showOp) {
        ServerPlayer player = source.getPlayer();
        boolean isOp = isOperator(source, player);

        if (showOp) {
            if (!isOp) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.op_title"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.destroy_near"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.destroy_id"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.reset"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.wand_id"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.info_id"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.fps_id"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.res_id"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.quality_id"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.cursor_id"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.max_fps"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.max_res"), false);
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.max_quality"), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("serverbrowser.help.title"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.create"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.destroy"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.open"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.navigation"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.input"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.mouse"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.wand"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.settings"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.cursor"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.list"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.controls_title"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.controls_right"), false);
        source.sendSuccess(() -> Component.translatable("serverbrowser.help.controls_left"), false);
        if (isOp) {
            source.sendSuccess(() -> Component.translatable("serverbrowser.help.op_hint"), false);
        }
        return 1;
    }

    private static boolean isOperator(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            return true;
        }
        if (source.getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
            return true;
        }
        if (source.permissions() instanceof LevelBasedPermissionSet levelSet) {
            return levelSet.level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
        }
        return false;
    }

    private static boolean checkOp(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return isOperator(source, player);
        }
        return true; // サーバーコンソール等は常に管理者権限
    }

    private static int createScreen(CommandSourceStack source, int width, int height) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerBrowserConfig config = ServerBrowserConfig.get();

            // 権限チェック (OP必須設定の場合)
            if (config.requireOpToCreate && !isOperator(source, player)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }

            // 最大サイズチェック
            if (width > config.maxDisplayWidth || height > config.maxDisplayHeight) {
                source.sendFailure(Component.translatable("serverbrowser.msg.max_size", config.maxDisplayWidth, config.maxDisplayHeight));
                return 0;
            }

            // 最大画面数チェック
            if (DisplayManager.getInstance().getAllScreens().size() >= config.maxTotalScreens) {
                source.sendFailure(Component.translatable("serverbrowser.msg.max_screens", config.maxTotalScreens));
                return 0;
            }

            ScreenInstance screen = DisplayManager.getInstance().createScreen(player, width, height);

            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.created", screen.getId(), width, height), false);

            String initialUrl = config.defaultUrl != null && !config.defaultUrl.isEmpty() ? config.defaultUrl : "https://www.google.com";
            DisplayManager.getInstance().navigateScreen(screen, initialUrl, player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int destroyScreen(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            boolean success = DisplayManager.getInstance().destroyScreenWithWand(player);
            return success ? 1 : 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int destroyNearScreen(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!isOperator(source, player)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }
            boolean success = DisplayManager.getInstance().removeNearestScreen(player, source.getServer());
            return success ? 1 : 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int destroyScreenById(CommandSourceStack source, int id) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!isOperator(source, player)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }
            boolean success = DisplayManager.getInstance().destroyScreenById(id, player, source.getServer());
            return success ? 1 : 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int resetScreens(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!isOperator(source, player)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }
            boolean success = DisplayManager.getInstance().resetAllScreens(player, source.getServer());
            return success ? 1 : 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int goHome(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            String homeUrl = com.serverbrowser.config.ServerBrowserConfig.get().defaultUrl;
            if (homeUrl == null || homeUrl.isEmpty()) {
                homeUrl = "https://www.google.com";
            }
            DisplayManager.getInstance().navigate(player, homeUrl, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int openUrl(CommandSourceStack source, String url) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().navigate(player, url, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int inputType(CommandSourceStack source, String text) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleTextInput(player, text, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int pressEnter(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleEnterKey(player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int pressBackspace(CommandSourceStack source, int count) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleBackspace(player, count, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int doClick(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleClick(player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int doRightClick(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleRightClick(player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int doubleClick(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleDoubleClick(player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int doToggleDrag(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().toggleDrag(player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int doScroll(CommandSourceStack source, int delta) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().scrollByCommand(player, delta, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int doBack(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleBack(player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int doForward(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleForward(player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int doReload(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            DisplayManager.getInstance().handleReload(player, source.getServer());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int listScreens(CommandSourceStack source) {
        Map<Integer, ScreenInstance> screens = DisplayManager.getInstance().getAllScreens();
        ServerBrowserConfig config = ServerBrowserConfig.get();
        if (screens.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.no_screens"), false);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable(
            "serverbrowser.msg.list_header", screens.size(),
            String.format("%.2f", config.maxScale), config.maxFps, config.maxQuality
        ), false);

        for (ScreenInstance s : screens.values()) {
            source.sendSuccess(() -> Component.translatable(
                "serverbrowser.msg.list_item",
                s.getId(),
                s.getWidthTiles(),
                s.getHeightTiles(),
                s.getRenderWidth(),
                s.getRenderHeight(),
                String.format("%.2f", s.getScale()),
                s.getFps(),
                s.getQuality(),
                s.getCurrentTarget()
            ), false);
        }
        return 1;
    }

    private static int showScreenInfo(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ScreenInstance screen = DisplayManager.getInstance().getHeldScreen(player);
            if (screen == null) {
                source.sendFailure(Component.translatable("serverbrowser.msg.hold_wand_required"));
                return 0;
            }
            displayScreenInfo(source, screen);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int showScreenInfoById(CommandSourceStack source, int id) {
        try {
            if (!checkOp(source)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }
            ScreenInstance screen = DisplayManager.getInstance().getScreen(id);
            if (screen == null) {
                source.sendFailure(Component.literal("§c[ServerBrowser] 画面 #" + id + " は存在しません。"));
                return 0;
            }
            displayScreenInfo(source, screen);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static void displayScreenInfo(CommandSourceStack source, ScreenInstance screen) {
        ServerBrowserConfig config = ServerBrowserConfig.get();
        int totalTiles = screen.getWidthTiles() * screen.getHeightTiles();
        String rawDim = screen.getLevel().dimension().toString();
        String dimension = rawDim.contains("/") ? rawDim.substring(rawDim.lastIndexOf('/') + 1).replace("]", "").trim() : rawDim;
        BlockPos pos = screen.getOriginWallPos();

        source.sendSuccess(() -> Component.translatable("serverbrowser.msg.info_header", screen.getId()), false);
        source.sendSuccess(() -> Component.translatable(
            "serverbrowser.msg.info_size",
            screen.getWidthTiles(), screen.getHeightTiles(), totalTiles, screen.getFacing().getName()
        ), false);
        source.sendSuccess(() -> Component.translatable(
            "serverbrowser.msg.info_resolution",
            String.format("%.2f", screen.getScale()),
            screen.getRenderWidth(), screen.getRenderHeight(),
            screen.getPixelWidth(), screen.getPixelHeight()
        ), false);
        source.sendSuccess(() -> Component.translatable(
            "serverbrowser.msg.info_cursor",
            String.format("%.2f", screen.getCursorScale()),
            String.format("%.2f", screen.getEffectiveCursorScale())
        ), false);
        source.sendSuccess(() -> Component.translatable(
            "serverbrowser.msg.info_fps",
            screen.getFps(), config.maxFps
        ), false);
        source.sendSuccess(() -> Component.translatable(
            "serverbrowser.msg.info_quality",
            screen.getQuality(), config.maxQuality
        ), false);
        source.sendSuccess(() -> Component.translatable(
            "serverbrowser.msg.info_url",
            screen.getCurrentTarget() != null ? screen.getCurrentTarget() : "about:blank"
        ), false);
        source.sendSuccess(() -> Component.translatable(
            "serverbrowser.msg.info_pos",
            dimension, pos.getX(), pos.getY(), pos.getZ()
        ), false);
    }

    private static int getWand(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            boolean success = DisplayManager.getInstance().reacquireWandNearest(player);
            return success ? 1 : 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int getWandById(CommandSourceStack source, int id) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!isOperator(source, player)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }
            boolean success = DisplayManager.getInstance().reacquireWand(player, id);
            return success ? 1 : 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // --- FPS コマンド ---
    private static int setScreenFps(CommandSourceStack source, int fps) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ScreenInstance screen = DisplayManager.getInstance().getHeldScreen(player);
            if (screen == null) {
                source.sendFailure(Component.translatable("serverbrowser.msg.hold_wand_required"));
                return 0;
            }

            ServerBrowserConfig config = ServerBrowserConfig.get();
            if (fps > config.maxFps) {
                source.sendFailure(Component.translatable("serverbrowser.msg.fps_limit_exceeded", fps, config.maxFps));
                return 0;
            }

            DisplayManager.getInstance().updateScreenProperties(screen, fps, null, null, source.getServer());
            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.fps_changed", screen.getId(), fps), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int setScreenFpsById(CommandSourceStack source, int id, int fps) {
        try {
            if (!checkOp(source)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }

            ScreenInstance screen = DisplayManager.getInstance().getScreen(id);
            if (screen == null) {
                source.sendFailure(Component.literal("§c[ServerBrowser] 画面 #" + id + " は存在しません。"));
                return 0;
            }

            ServerBrowserConfig config = ServerBrowserConfig.get();
            if (fps > config.maxFps) {
                source.sendFailure(Component.translatable("serverbrowser.msg.fps_limit_exceeded", fps, config.maxFps));
                return 0;
            }

            DisplayManager.getInstance().updateScreenProperties(screen, fps, null, null, source.getServer());
            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.fps_changed", screen.getId(), fps), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // --- JPEG 画質コマンド ---
    private static int setScreenQuality(CommandSourceStack source, int quality) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ScreenInstance screen = DisplayManager.getInstance().getHeldScreen(player);
            if (screen == null) {
                source.sendFailure(Component.translatable("serverbrowser.msg.hold_wand_required"));
                return 0;
            }

            ServerBrowserConfig config = ServerBrowserConfig.get();
            if (quality > config.maxQuality) {
                source.sendFailure(Component.translatable("serverbrowser.msg.quality_limit_exceeded", quality, config.maxQuality));
                return 0;
            }

            DisplayManager.getInstance().updateScreenProperties(screen, null, quality, null, source.getServer());
            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.quality_changed", screen.getId(), quality), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int setScreenQualityById(CommandSourceStack source, int id, int quality) {
        try {
            if (!checkOp(source)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }

            ScreenInstance screen = DisplayManager.getInstance().getScreen(id);
            if (screen == null) {
                source.sendFailure(Component.literal("§c[ServerBrowser] 画面 #" + id + " は存在しません。"));
                return 0;
            }

            ServerBrowserConfig config = ServerBrowserConfig.get();
            if (quality > config.maxQuality) {
                source.sendFailure(Component.translatable("serverbrowser.msg.quality_limit_exceeded", quality, config.maxQuality));
                return 0;
            }

            DisplayManager.getInstance().updateScreenProperties(screen, null, quality, null, source.getServer());
            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.quality_changed", screen.getId(), quality), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // --- 解像度スケールコマンド ---
    private static int setScreenScale(CommandSourceStack source, double rawScale) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ScreenInstance screen = DisplayManager.getInstance().getHeldScreen(player);
            if (screen == null) {
                source.sendFailure(Component.translatable("serverbrowser.msg.hold_wand_required"));
                return 0;
            }

            double scale = Math.round(rawScale * 100.0) / 100.0;
            ServerBrowserConfig config = ServerBrowserConfig.get();
            if (scale > config.maxScale + 0.001) {
                source.sendFailure(Component.translatable("serverbrowser.msg.scale_limit_exceeded", String.format("%.2f", scale), String.format("%.2f", config.maxScale)));
                return 0;
            }

            DisplayManager.getInstance().updateScreenProperties(screen, null, null, scale, source.getServer());
            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.scale_changed", screen.getId(), String.format("%.2f", scale), screen.getRenderWidth(), screen.getRenderHeight()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int setScreenScaleById(CommandSourceStack source, int id, double rawScale) {
        try {
            if (!checkOp(source)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }

            ScreenInstance screen = DisplayManager.getInstance().getScreen(id);
            if (screen == null) {
                source.sendFailure(Component.literal("§c[ServerBrowser] 画面 #" + id + " は存在しません。"));
                return 0;
            }

            double scale = Math.round(rawScale * 100.0) / 100.0;
            ServerBrowserConfig config = ServerBrowserConfig.get();
            if (scale > config.maxScale + 0.001) {
                source.sendFailure(Component.translatable("serverbrowser.msg.scale_limit_exceeded", String.format("%.2f", scale), String.format("%.2f", config.maxScale)));
                return 0;
            }

            DisplayManager.getInstance().updateScreenProperties(screen, null, null, scale, source.getServer());
            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.scale_changed", screen.getId(), String.format("%.2f", scale), screen.getRenderWidth(), screen.getRenderHeight()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // --- カーソル倍率コマンド ---
    private static int setScreenCursor(CommandSourceStack source, double rawScale) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ScreenInstance screen = DisplayManager.getInstance().getHeldScreen(player);
            if (screen == null) {
                source.sendFailure(Component.translatable("serverbrowser.msg.hold_wand_required"));
                return 0;
            }

            double scale = Math.round(rawScale * 100.0) / 100.0;
            if (scale < 0.1 || scale > 10.0) {
                source.sendFailure(Component.translatable("serverbrowser.msg.cursor_limit_exceeded", String.format("%.2f", scale)));
                return 0;
            }

            DisplayManager.getInstance().updateScreenCursor(screen, scale, source.getServer());
            source.sendSuccess(() -> Component.translatable(
                "serverbrowser.msg.cursor_changed",
                screen.getId(),
                String.format("%.2f", scale),
                String.format("%.2f", screen.getEffectiveCursorScale())
            ), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int setScreenCursorById(CommandSourceStack source, int id, double rawScale) {
        try {
            if (!checkOp(source)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }

            ScreenInstance screen = DisplayManager.getInstance().getScreen(id);
            if (screen == null) {
                source.sendFailure(Component.literal("§c[ServerBrowser] 画面 #" + id + " は存在しません。"));
                return 0;
            }

            double scale = Math.round(rawScale * 100.0) / 100.0;
            if (scale < 0.1 || scale > 10.0) {
                source.sendFailure(Component.translatable("serverbrowser.msg.cursor_limit_exceeded", String.format("%.2f", scale)));
                return 0;
            }

            DisplayManager.getInstance().updateScreenCursor(screen, scale, source.getServer());
            source.sendSuccess(() -> Component.translatable(
                "serverbrowser.msg.cursor_changed",
                screen.getId(),
                String.format("%.2f", scale),
                String.format("%.2f", screen.getEffectiveCursorScale())
            ), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // --- サーバー全体の最大上限設定 (OP専用) ---
    private static int setServerMaxFps(CommandSourceStack source, int fps) {
        try {
            if (!checkOp(source)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }

            ServerBrowserConfig config = ServerBrowserConfig.get();
            config.maxFps = Math.max(1, Math.min(60, fps));
            ServerBrowserConfig.save();

            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.max_fps_set", config.maxFps), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int setServerMaxQuality(CommandSourceStack source, int quality) {
        try {
            if (!checkOp(source)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }

            ServerBrowserConfig config = ServerBrowserConfig.get();
            config.maxQuality = Math.max(10, Math.min(100, quality));
            ServerBrowserConfig.save();

            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.max_quality_set", config.maxQuality), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int setServerMaxScale(CommandSourceStack source, double rawScale) {
        try {
            if (!checkOp(source)) {
                source.sendFailure(Component.translatable("serverbrowser.msg.op_required"));
                return 0;
            }

            double scale = Math.round(rawScale * 100.0) / 100.0;
            ServerBrowserConfig config = ServerBrowserConfig.get();
            config.maxScale = Math.max(0.01, scale);
            ServerBrowserConfig.save();

            source.sendSuccess(() -> Component.translatable("serverbrowser.msg.max_scale_set", String.format("%.2f", config.maxScale)), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
}
