package com.cobblemoon.autoqiqi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.BattleMode;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.battle.TrainerBattleEngine;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;

import net.minecraft.client.MinecraftClient;

@Mixin(BattleGeneralActionSelection.class)
public abstract class BattleGeneralActionSelectionMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void injectInit(CallbackInfo ci) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        var self = (BattleGeneralActionSelection) (Object) this;

        if (self.getRequest().getResponse() != null) return;

        if (CaptureEngine.get().isActive() && AutoQiqiClient.getBattleMode() != BattleMode.OFF) {
            // Mixin firing again means previous ball hit the target (battle resumed)
            boolean wasWaitingForHit = CaptureEngine.get().isWaitingForBallHit();
            if (wasWaitingForHit) {
                CaptureEngine.chatBall("Hit confirmed (mixin: action screen shown again)");
                CaptureEngine.get().onBallHitConfirmed();
            }

            // If a ball throw is still being prepared, don't schedule another action
            if (CaptureEngine.get().isPendingBallThrow()) {
                AutoQiqiClient.log("Mixin", "GeneralAction: ball throw still pending, ignoring");
                return;
            }

            // After a ball hit, wait longer for the shake animation to complete visually
            boolean hitJustConfirmed = CaptureEngine.get().consumeBallHitConfirmed();
            long delay = hitJustConfirmed
                    ? CaptureEngine.get().getBallHitExtraDelay()
                    : config.battleSelectDelay;
            AutoQiqiClient.log("Mixin", "GeneralAction: CaptureEngine active, delay=" + delay + " (hitConfirmed=" + hitJustConfirmed + ")");
            AutoQiqiClient.runLater(() -> {
                // Use current action selection: after ball hit the UI may have recreated the screen (new instance),
                // so self != getCurrentActionSelection() would wrongly skip and leave battle idle.
                var current = self.getBattleGUI().getCurrentActionSelection();
                if (!(current instanceof BattleGeneralActionSelection currentSelection)) {
                    AutoQiqiClient.log("Mixin", "GeneralAction: no longer on action selection, skipping");
                    return;
                }
                if (currentSelection.getRequest().getResponse() != null) {
                    AutoQiqiClient.log("Mixin", "GeneralAction: response already set, skipping");
                    return;
                }
                CaptureEngine.handleCaptureAction(currentSelection);
            }, delay);
            return;
        }

        if (AutoQiqiClient.shouldAutoFight()) {
            boolean isTrainer = AutoQiqiClient.getBattleMode() == BattleMode.TRAINER;
            AutoQiqiClient.log("Mixin", "GeneralAction: AutoBattle mode" + (isTrainer ? " (Trainer)" : "") + ", auto-fighting");
            AutoQiqiClient.runLater(() -> {
                if (self.getBattleGUI().getCurrentActionSelection() != self
                        || self.getRequest().getResponse() != null) return;

                self.playDownSound(MinecraftClient.getInstance().getSoundManager());

                if (isTrainer) {
                    boolean forceSwitch = self.getRequest().getForceSwitch();
                    boolean trapped = self.getRequest().getMoveSet() != null
                            && self.getRequest().getMoveSet().getTrapped();

                    TrainerBattleEngine.GeneralChoice choice =
                            TrainerBattleEngine.get().decideGeneralAction(forceSwitch, trapped);

                    if (choice == TrainerBattleEngine.GeneralChoice.SWITCH) {
                        self.getBattleGUI().changeActionSelection(
                                new BattleSwitchPokemonSelection(self.getBattleGUI(), self.getRequest()));
                    } else {
                        self.getBattleGUI().changeActionSelection(
                                new BattleMoveSelection(self.getBattleGUI(), self.getRequest()));
                    }
                } else {
                    var forceMove = self.getRequest().getMoveSet() != null
                            && self.getRequest().getMoveSet().getTrapped();

                    if (forceMove || Math.random() >= config.battleSwitchChance) {
                        self.getBattleGUI().changeActionSelection(
                                new BattleMoveSelection(self.getBattleGUI(), self.getRequest()));
                    } else {
                        self.getBattleGUI().changeActionSelection(
                                new BattleSwitchPokemonSelection(self.getBattleGUI(), self.getRequest()));
                    }
                }
            }, config.battleSelectDelay);
        }
    }

}
