package com.cobblemoon.autoqiqi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.net.battle.BattleCaptureEndHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleCaptureEndPacket;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;

import net.minecraft.client.MinecraftClient;

/**
 * When the client receives BattleCaptureEndPacket, the server has finished
 * a capture attempt (ball hit the Pokemon → either caught or broke out).
 * We use this as the authoritative "ball hit" signal so that only physical
 * misses (timeout with no packet) count toward MAX_MISSES.
 *
 * When the packet fires first, BattleGeneralActionSelection init may not run
 * (game can show another screen after breakout). So we schedule sending the
 * next action after the shake animation; when the UI is ready we send.
 */
@Mixin(BattleCaptureEndHandler.class)
public abstract class BattleCaptureEndHandlerMixin {

    // At runtime Fabric uses intermediary mappings: client class is class_310 (MinecraftClient).
    @Inject(
            method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleCaptureEndPacket;Lnet/minecraft/class_310;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void onCaptureEnd(BattleCaptureEndPacket packet, MinecraftClient client, CallbackInfo ci) {
        if (!CaptureEngine.get().isActive()) return;
        if (!CaptureEngine.get().isWaitingForBallHit()) return;

        boolean succeeded = packet.getSucceeded();
        AutoQiqiClient.log("Capture", "Ball HIT confirmed via BattleCaptureEndPacket (succeeded=" + succeeded + ")");
        CaptureEngine.get().onBallHitConfirmed();

        if (!succeeded) {
            // Pokemon broke free — the battle is still minimized from when CAPTURE was chosen.
            // Un-minimize it so the action selection UI reappears, triggering the mixin
            // to decide the next capture action. Retry several times in case of timing issues.
            long baseDelay = CaptureEngine.BALL_HIT_PACKET_FOLLOW_UP_DELAY_MS;
            for (int i = 0; i < 4; i++) {
                long delay = baseDelay + i * 2000L;
                AutoQiqiClient.runLater(() -> {
                    CaptureEngine.get().unminimizeBattleIfNeeded("breakout follow-up");
                    CaptureEngine.trySendNextCaptureActionIfReady();
                }, delay);
            }
        }
    }
}
