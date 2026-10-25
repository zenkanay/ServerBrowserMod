package com.serverbrowser;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.serverbrowser.command.BrowserCommand;
import com.serverbrowser.display.DisplayManager;
import com.serverbrowser.display.ScreenInstance;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerBrowserMod implements ModInitializer {
    public static final String MOD_ID = "serverbrowser";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Starting ServerBrowser Mod (Multi-Screen Real Browser with 1:1 Remote Wands)");

        // コマンド登録 (/browser, /google -> /browser open へ統合)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            BrowserCommand.register(dispatcher);
            dispatcher.register(
                Commands.literal("google")
                    .then(Commands.argument("query", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("§7[ServerBrowser] ※ /google は /browser open に統合されました。"), false);
                            return BrowserCommand.openUrl(ctx.getSource(), StringArgumentType.getString(ctx, "query"));
                        })
                    )
            );
        });

        // 毎tickの視線ポインタ追従処理（リモコン所持時のみ）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DisplayManager.getInstance().onServerTick(server);
        });

        // リモコン棒の右クリック（クリック / 素早く2回でダブルクリック / Shift+右クリックでドラッグ開始/終了）
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                if (DisplayManager.getInstance().handleWandUse(serverPlayer, hand)) {
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // リモコン棒の左クリック（ブロック攻撃 / 空振りでの上スクロール、壁ブロック保護）
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                // ブラウザ画面の壁ブロックかどうか判定
                for (ScreenInstance s : DisplayManager.getInstance().getAllScreens().values()) {
                    if (s.isWallBlock(pos)) {
                        // 壁ブロックの破壊を完全防止！
                        ScreenInstance held = DisplayManager.getInstance().getHeldScreen(serverPlayer);
                        if (held != null && held.getId() == s.getId()) {
                            DisplayManager.getInstance().handleWandAttack(serverPlayer); // 上スクロール
                        }
                        return InteractionResult.SUCCESS;
                    }
                }
            }
            return InteractionResult.PASS;
        });

        // 額縁の右クリック操作（額縁の回転・変更を誰であっても100%完全防止！）
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!level.isClientSide() && entity instanceof ItemFrame frame && player instanceof ServerPlayer serverPlayer) {
                for (ScreenInstance s : DisplayManager.getInstance().getAllScreens().values()) {
                    if (s.isFrameOfScreen(frame)) {
                        // ディスプレイの額縁: 対応するリモコン棒をメインハンドに持っている場合のみ画面操作！
                        ScreenInstance held = DisplayManager.getInstance().getHeldScreen(serverPlayer);
                        if (held != null && held.getId() == s.getId()) {
                            DisplayManager.getInstance().handleFrameInteraction(serverPlayer, frame);
                            return InteractionResult.SUCCESS;
                        }
                        // 素手や他アイテム、または別画面のリモコン棒なら回転や操作を100%阻止！
                        return InteractionResult.FAIL;
                    }
                }
                // ディスプレイ外の額縁であっても、棒を持っている間は回転させない
                if (DisplayManager.getInstance().isHoldingRemoteWand(serverPlayer)) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 額縁の左クリック操作（額縁の攻撃・アイテムドロップ・破壊を誰であっても100%完全防止！）
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!level.isClientSide() && entity instanceof ItemFrame frame && player instanceof ServerPlayer serverPlayer) {
                for (ScreenInstance s : DisplayManager.getInstance().getAllScreens().values()) {
                    if (s.isFrameOfScreen(frame)) {
                        // ディスプレイの額縁: 対応するリモコン棒をメインハンドに持っている場合のみ上スクロール！
                        ScreenInstance held = DisplayManager.getInstance().getHeldScreen(serverPlayer);
                        if (held != null && held.getId() == s.getId()) {
                            DisplayManager.getInstance().handleWandAttack(serverPlayer); // 上スクロール
                        }
                        // 誰であっても攻撃・破壊・脱落は100%完全阻止！
                        return InteractionResult.SUCCESS;
                    }
                }
                // 棒を持っている間は画面外の額縁も破壊しない
                if (DisplayManager.getInstance().isHoldingRemoteWand(serverPlayer)) {
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // プレイヤー参加時のパケット再送
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            DisplayManager.getInstance().onPlayerJoin(handler.getPlayer());
        });

        // サーバー起動時のディスプレイ復元（永続化ロード）
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Server started - restoring saved browser displays...");
            DisplayManager.getInstance().loadScreens(server);
        });

        // サーバー停止時の画面セーブ & CDP プロセス終了（額縁やブロックは絶対に消さない！）
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping - saving browser displays to disk...");
            DisplayManager.getInstance().onServerStopping(server);
        });

        LOGGER.info("ServerBrowser Mod initialized successfully! Active engine: {}",
            DisplayManager.getInstance().getActiveEngine().getEngineName());
    }
}
