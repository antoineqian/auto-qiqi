package com.cobblemoon.autoqiqi.mixin;

import com.cobblemoon.autoqiqi.fish.AutofishEngine;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FishingBobberEntity.class)
public class FishHookEntityMixin {

    @Shadow private int hookCountdown;

    @Inject(method = "tickFishingLogic", at = @At("TAIL"))
    private void tickFishingLogic(BlockPos blockPos_1, CallbackInfo ci) {
        if (AutofishEngine.get() != null) {
            AutofishEngine.get().tickFishingLogic(
                    ((FishingBobberEntity) (Object) this).getOwner(), hookCountdown);
        }
    }
}
