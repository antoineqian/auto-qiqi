package com.cobblemoon.autoqiqi.mixin;

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

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

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
