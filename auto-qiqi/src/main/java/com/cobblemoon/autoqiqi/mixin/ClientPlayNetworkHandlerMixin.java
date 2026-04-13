package com.cobblemoon.autoqiqi.mixin;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "sendCommand", at = @At("HEAD"))
    private void onSendCommand(String command, CallbackInfoReturnable<Boolean> cir) {
        if (command != null && command.trim().toLowerCase().startsWith("warp")) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.isOnThread()) {
                AutoQiqiClient.stopAllAutomaticFeaturesForWarp();
            }
        }
    }

    @Inject(method = "sendChatMessage", at = @At("HEAD"))
    private void onSendChatMessage(String content, CallbackInfo ci) {
        if (content != null && content.trim().toLowerCase().startsWith("/warp")) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.isOnThread()) {
                AutoQiqiClient.stopAllAutomaticFeaturesForWarp();
            }
        }
    }
}
