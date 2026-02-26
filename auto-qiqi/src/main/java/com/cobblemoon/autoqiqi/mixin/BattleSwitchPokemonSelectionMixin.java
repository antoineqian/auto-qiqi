package com.cobblemoon.autoqiqi.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection.SwitchTile;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.BattleMode;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.battle.TrainerBattleEngine;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;

import net.minecraft.client.MinecraftClient;

@Mixin(BattleSwitchPokemonSelection.class)
public abstract class BattleSwitchPokemonSelectionMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void injectInit(CallbackInfo ci) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        var self = (BattleSwitchPokemonSelection) (Object) this;

        if (self.getRequest().getResponse() != null) return;

        if (CaptureEngine.get().isActive()) {
            AutoQiqiClient.log("Mixin", "SwitchSelection: CaptureEngine active, isReviving=" + self.isReviving());
            AutoQiqiClient.runLater(() -> {
                if (self.getBattleGUI().getCurrentActionSelection() != self
                        || self.getRequest().getResponse() != null) {
                    AutoQiqiClient.log("Mixin", "SwitchSelection: stale request, skipping");
                    return;
                }

                self.playDownSound(MinecraftClient.getInstance().getSoundManager());

                List<SwitchTile> available = self.getTiles().stream()
                        .filter(t -> self.isReviving()
                                ? t.isFainted()
                                : (!t.isFainted() && !t.isCurrentlyInBattle()))
                        .toList();

                AutoQiqiClient.log("Mixin", "SwitchSelection: " + available.size() + " available: "
                        + available.stream().map(t -> t.getPokemon().getSpecies().getName()).toList());

                SwitchTile chosen = CaptureEngine.get().chooseSwitchFromTiles(available);
                if (chosen != null) {
                    AutoQiqiClient.log("Mixin", "SwitchSelection: switching to " + chosen.getPokemon().getSpecies().getName());
                    self.getBattleGUI().selectAction(
                            self.getRequest(),
                            new SwitchActionResponse(chosen.getPokemon().getUuid()));
                } else {
                    AutoQiqiClient.log("Mixin", "SwitchSelection: no valid switch target, backing out");
                    self.getBattleGUI().changeActionSelection(null);
                }
            }, config.battleSelectDelay);
            return;
        }

        if (AutoQiqiClient.shouldAutoFight()) {
            boolean isTrainer = AutoQiqiClient.getBattleMode() == BattleMode.TRAINER;
            AutoQiqiClient.runLater(() -> {
                if (self.getBattleGUI().getCurrentActionSelection() != self
                        || self.getRequest().getResponse() != null) {
                    AutoQiqiClient.log("Mixin", "SwitchSelection: stale request (autofight), skipping");
                    return;
                }

                List<SwitchTile> tiles = self.getTiles().stream()
                        .filter(t -> self.isReviving()
                                ? t.isFainted()
                                : (!t.isFainted() && !t.isCurrentlyInBattle()))
                        .toList();
                self.playDownSound(MinecraftClient.getInstance().getSoundManager());

                SwitchTile chosen;
                if (isTrainer) {
                    AutoQiqiClient.log("Mixin", "SwitchSelection: Trainer mode, " + tiles.size() + " available");
                    chosen = TrainerBattleEngine.get().chooseBestSwitch(tiles);
                } else {
                    chosen = tiles.isEmpty() ? null : tiles.get((int) (Math.random() * tiles.size()));
                }

                if (chosen != null) {
                    AutoQiqiClient.log("Mixin", "SwitchSelection: switching to " + chosen.getPokemon().getSpecies().getName());
                    self.getBattleGUI().selectAction(
                            self.getRequest(),
                            new SwitchActionResponse(chosen.getPokemon().getUuid()));
                } else {
                    AutoQiqiClient.log("Mixin", "SwitchSelection: no valid switch target (autofight)");
                    self.getBattleGUI().changeActionSelection(null);
                }
            }, config.battleSelectDelay + 800);
        }
    }
}
