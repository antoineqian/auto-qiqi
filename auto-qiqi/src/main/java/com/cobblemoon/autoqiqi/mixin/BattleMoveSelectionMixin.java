package com.cobblemoon.autoqiqi.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.BattleMode;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.battle.TrainerBattleEngine;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;

import net.minecraft.client.MinecraftClient;

@Mixin(BattleMoveSelection.class)
public abstract class BattleMoveSelectionMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void injectInit(CallbackInfo ci) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        var self = (BattleMoveSelection) (Object) this;

        if (self.getRequest().getResponse() != null) return;

        if (CaptureEngine.get().isActive()) {
            AutoQiqiClient.log("Mixin", "MoveSelection: CaptureEngine active");
            AutoQiqiClient.runLater(() -> {
                if (self.getBattleGUI().getCurrentActionSelection() != self
                        || self.getRequest().getResponse() != null) {
                    AutoQiqiClient.log("Mixin", "MoveSelection: stale request, skipping");
                    return;
                }

                List<MoveTile> selectable = self.getMoveTiles().stream()
                        .filter(t -> t.getSelectable()).toList();

                AutoQiqiClient.log("Mixin", "MoveSelection: " + selectable.size() + " selectable moves: "
                        + selectable.stream().map(t -> t.getMove().getMove()).toList());

                MoveTile chosen = CaptureEngine.get().chooseMoveFromTiles(selectable);
                if (chosen != null) {
                    AutoQiqiClient.log("Mixin", "MoveSelection: clicking move '" + chosen.getMove().getMove() + "'");
                    if (self.getRequest().getActivePokemon().getFormat()
                            .getBattleType().getPokemonPerSide() == 1) {
                        chosen.onClick();
                    } else {
                        self.playDownSound(MinecraftClient.getInstance().getSoundManager());
                        self.getBattleGUI().changeActionSelection(
                                new BattleTargetSelection(
                                        self.getBattleGUI(), self.getRequest(),
                                        chosen.getMove(), null, null));
                    }
                } else {
                    AutoQiqiClient.log("Mixin", "MoveSelection: no valid move found, backing out");
                    self.playDownSound(MinecraftClient.getInstance().getSoundManager());
                    self.getBattleGUI().changeActionSelection(null);
                }
            }, config.battleSelectDelay);
            return;
        }

        if (AutoQiqiClient.shouldAutoFight()) {
            boolean isTrainer = AutoQiqiClient.getBattleMode() == BattleMode.TRAINER;
            AutoQiqiClient.runLater(() -> {
                if (self.getBattleGUI().getCurrentActionSelection() != self
                        || self.getRequest().getResponse() != null) return;

                List<MoveTile> tiles = self.getMoveTiles().stream()
                        .filter(t -> t.getSelectable()).toList();

                MoveTile chosen;
                if (isTrainer) {
                    AutoQiqiClient.log("Mixin", "MoveSelection: Trainer mode, " + tiles.size() + " selectable moves");
                    chosen = TrainerBattleEngine.get().chooseBestMove(tiles);
                } else {
                    chosen = tiles.isEmpty() ? null : tiles.get((int) (Math.random() * tiles.size()));
                }

                if (chosen != null) {
                    if (self.getRequest().getActivePokemon().getFormat()
                            .getBattleType().getPokemonPerSide() == 1) {
                        chosen.onClick();
                    } else {
                        self.playDownSound(MinecraftClient.getInstance().getSoundManager());
                        self.getBattleGUI().changeActionSelection(
                                new BattleTargetSelection(
                                        self.getBattleGUI(), self.getRequest(),
                                        chosen.getMove(), null, null));
                    }
                } else {
                    self.playDownSound(MinecraftClient.getInstance().getSoundManager());
                    self.getBattleGUI().changeActionSelection(null);
                }
            }, config.battleSelectDelay);
        }
    }
}
