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

        AutoQiqiClient.log("Capture", "Ball HIT confirmed via BattleCaptureEndPacket (succeeded=" + packet.getSucceeded() + ")");
        CaptureEngine.get().onBallHitConfirmed();
    }
}
