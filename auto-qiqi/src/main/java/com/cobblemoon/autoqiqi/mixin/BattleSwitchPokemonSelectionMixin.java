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
import com.cobblemoon.autoqiqi.battle.BattleDecisionRouter;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;

import net.minecraft.client.MinecraftClient;

@Mixin(BattleSwitchPokemonSelection.class)
public abstract class BattleSwitchPokemonSelectionMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void injectInit(CallbackInfo ci) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        var self = (BattleSwitchPokemonSelection) (Object) this;

        AutoQiqiClient.logDebug("Mixin", "SwitchSelection: screen constructed (response=" + (self.getRequest().getResponse() != null) + ")");
        if (self.getRequest().getResponse() != null) return;

        if (CaptureEngine.get().isActive()) {
            AutoQiqiClient.logDebug("Mixin", "SwitchSelection: CaptureEngine active, isReviving=" + self.isReviving());
            AutoQiqiClient.runLater(() -> {
                var current = self.getBattleGUI().getCurrentActionSelection();
                if (!(current instanceof BattleSwitchPokemonSelection currentSelection)) {
                    AutoQiqiClient.logDebug("Mixin", "SwitchSelection: no longer on switch screen, skipping");
                    return;
                }
                if (currentSelection.getRequest().getResponse() != null) {
                    AutoQiqiClient.logDebug("Mixin", "SwitchSelection: response already set, skipping");
                    return;
                }

                currentSelection.playDownSound(MinecraftClient.getInstance().getSoundManager());

                List<SwitchTile> available = currentSelection.getTiles().stream()
                        .filter(t -> currentSelection.isReviving()
                                ? t.isFainted()
                                : (!t.isFainted() && !t.isCurrentlyInBattle()))
                        .toList();

                AutoQiqiClient.logDebug("Mixin", "SwitchSelection: " + available.size() + " available: "
                        + available.stream().map(t -> t.getPokemon().getSpecies().getName()).toList());

                SwitchTile chosen = CaptureEngine.get().chooseSwitchFromTiles(available);
                if (chosen != null) {
                    AutoQiqiClient.logDebug("Mixin", "SwitchSelection: switching to " + chosen.getPokemon().getSpecies().getName());
                    currentSelection.getBattleGUI().selectAction(
                            currentSelection.getRequest(),
                            new SwitchActionResponse(chosen.getPokemon().getUuid()));
                } else {
                    AutoQiqiClient.logDebug("Mixin", "SwitchSelection: no valid switch target — doing nothing (user intervention needed)");
                }
            }, config.battleSelectDelay);
            return;
        }

        if (AutoQiqiClient.shouldAutoFight()) {
            AutoQiqiClient.runLater(() -> {
                var current = self.getBattleGUI().getCurrentActionSelection();
                if (!(current instanceof BattleSwitchPokemonSelection currentSelection)) {
                    AutoQiqiClient.logDebug("Mixin", "SwitchSelection: no longer on switch screen (autofight), skipping");
                    return;
                }
                if (currentSelection.getRequest().getResponse() != null) {
                    AutoQiqiClient.logDebug("Mixin", "SwitchSelection: response already set (autofight), skipping");
                    return;
                }

                List<SwitchTile> tiles = currentSelection.getTiles().stream()
                        .filter(t -> currentSelection.isReviving()
                                ? t.isFainted()
                                : (!t.isFainted() && !t.isCurrentlyInBattle()))
                        .toList();
                currentSelection.playDownSound(MinecraftClient.getInstance().getSoundManager());

                SwitchTile chosen = BattleDecisionRouter.chooseSwitch(tiles);

                if (chosen != null) {
                    AutoQiqiClient.logDebug("Mixin", "SwitchSelection: switching to " + chosen.getPokemon().getSpecies().getName());
                    currentSelection.getBattleGUI().selectAction(
                            currentSelection.getRequest(),
                            new SwitchActionResponse(chosen.getPokemon().getUuid()));
                } else {
                    AutoQiqiClient.logDebug("Mixin", "SwitchSelection: no valid switch target (autofight) — doing nothing (user intervention needed)");
                }
            }, config.battleSelectDelay + 800);
        }
    }
}
