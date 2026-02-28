package com.cobblemoon.autoqiqi.mixin;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.fish.AutofishEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(method = "onPlaySound", at = @At("HEAD"))
    public void onPlaySound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread() && AutofishEngine.get() != null) {
            AutofishEngine.get().handlePacket(packet);
        }
    }

    @Inject(method = "onEntityVelocityUpdate", at = @At("HEAD"))
    public void onVelocityUpdate(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread() && AutofishEngine.get() != null) {
            AutofishEngine.get().handlePacket(packet);
        }
    }

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    public void onChatMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread() && AutofishEngine.get() != null) {
            AutofishEngine.get().handleChat(packet);
        }
    }
}
