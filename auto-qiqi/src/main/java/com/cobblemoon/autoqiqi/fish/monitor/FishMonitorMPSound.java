package com.cobblemoon.autoqiqi.fish.monitor;

import com.cobblemoon.autoqiqi.fish.AutofishEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundEvent;

public class FishMonitorMPSound implements FishMonitorMP {

    private static final double HOOKSOUND_DISTANCESQ_THRESHOLD = 25D;
    private static final String VANILLA_SPLASH = "minecraft:entity.fishing_bobber.splash";
    private static final String COBBLEMON_NOTIFICATION = "cobblemon:fishing.notification";

    @Override
    public void hookTick(AutofishEngine autofish, MinecraftClient minecraft, FishingBobberEntity hook) {}

    @Override
    public void handleHookRemoved() {}

    @Override
    public void handlePacket(AutofishEngine autofish, Object packet, MinecraftClient minecraft) {
        if (packet instanceof PlaySoundS2CPacket soundPacket) {
            SoundEvent soundEvent = soundPacket.getSound().value();
            String soundName = soundEvent.getId().toString();

            if (isFishingBiteSound(soundName)) {
                if (minecraft.player != null) {
                    FishingBobberEntity hook = minecraft.player.fishHook;
                    if (hook != null) {
                        if (hook.squaredDistanceTo(soundPacket.getX(), soundPacket.getY(), soundPacket.getZ())
                                < HOOKSOUND_DISTANCESQ_THRESHOLD) {
                            autofish.catchFish();
                        }
                    }
                }
            }
        }
    }

    private boolean isFishingBiteSound(String soundName) {
        return soundName.equalsIgnoreCase(VANILLA_SPLASH)
                || soundName.equalsIgnoreCase("entity.fishing_bobber.splash")
                || soundName.equalsIgnoreCase(COBBLEMON_NOTIFICATION)
                || soundName.equalsIgnoreCase("fishing.notification");
    }
}
