package com.cobblemoon.autoqiqi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;

import net.minecraft.client.MinecraftClient;

@Mixin(BattleTargetSelection.class)
public abstract class BattleTargetSelectionMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void injectInit(CallbackInfo ci) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (AutoQiqiClient.shouldAutoFight()) {
            var self = (BattleTargetSelection) (Object) this;

            if (self.getRequest().getResponse() == null) {
                AutoQiqiClient.runLater(() -> {
                    if (self.getBattleGUI().getCurrentActionSelection() == self
                            && self.getRequest().getResponse() == null) {
                        var tiles = self.getTargetTiles().stream()
                                .filter(t -> t.getSelectable()).toList();

                        if (tiles.size() > 0) {
                            var tile = tiles.get((int) (Math.random() * tiles.size()));
                            tile.onClick();
                        } else {
                            self.playDownSound(MinecraftClient.getInstance().getSoundManager());
                            self.getBattleGUI().changeActionSelection(null);
                        }
                    }
                }, config.battleSelectDelay);
            }
        }
    }
}
