package com.cobblemoon.autoqiqi.fish.monitor;

import com.cobblemoon.autoqiqi.fish.AutofishEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.projectile.FishingBobberEntity;

public interface FishMonitorMP {
    void hookTick(AutofishEngine autofish, MinecraftClient minecraft, FishingBobberEntity hook);
    void handleHookRemoved();
    void handlePacket(AutofishEngine autofish, Object packet, MinecraftClient minecraft);
}
