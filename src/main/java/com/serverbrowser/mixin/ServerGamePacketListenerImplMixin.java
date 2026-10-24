package com.serverbrowser.mixin;

import com.serverbrowser.display.DisplayManager;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * 遠くから何もない空中を左クリックした時の腕振りパケット（ServerboundSwingPacket）を捕捉
     * リモコン棒を持っている場合、安全に保留キューに登録してTick遅延評価（右クリック誤爆を100%排除！）
     */
    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void onHandleAnimate(ServerboundSwingPacket packet, CallbackInfo ci) {
        if (player != null && DisplayManager.getInstance().isHoldingRemoteWand(player)) {
            DisplayManager.getInstance().queueSwing(player);
        }
    }
}
