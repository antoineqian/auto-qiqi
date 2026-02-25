package com.cobblemoon.autoqiqi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.CobblemonClient;
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

        if (CaptureEngine.get().isActive()) {
            // Mixin firing again means previous ball hit the target (battle resumed)
            boolean wasWaitingForHit = CaptureEngine.get().isWaitingForBallHit();
            if (wasWaitingForHit) {
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
                if (self.getBattleGUI().getCurrentActionSelection() != self
                        || self.getRequest().getResponse() != null) {
                    AutoQiqiClient.log("Mixin", "GeneralAction: stale request, skipping");
                    return;
                }
                handleCapture(self);
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

    private void handleCapture(BattleGeneralActionSelection self) {
        CaptureEngine engine = CaptureEngine.get();
        boolean forceSwitch = self.getRequest().getForceSwitch();

        CaptureEngine.GeneralChoice choice = engine.decideGeneralAction(forceSwitch);
        AutoQiqiClient.log("Mixin", "GeneralAction capture choice=" + choice + " action=" + engine.getCurrentAction() + " forceSwitch=" + forceSwitch);
        self.playDownSound(MinecraftClient.getInstance().getSoundManager());

        switch (choice) {
            case FIGHT -> {
                AutoQiqiClient.log("Mixin", "GeneralAction -> FIGHT (opening move selection)");
                self.getBattleGUI().changeActionSelection(
                    new BattleMoveSelection(self.getBattleGUI(), self.getRequest()));
            }
            case SWITCH -> {
                AutoQiqiClient.log("Mixin", "GeneralAction -> SWITCH (opening switch selection)");
                self.getBattleGUI().changeActionSelection(
                    new BattleSwitchPokemonSelection(self.getBattleGUI(), self.getRequest()));
            }
            case CAPTURE -> {
                var battle = CobblemonClient.INSTANCE.getBattle();
                if (battle != null) {
                    battle.setMinimised(true);
                    AutoQiqiClient.log("Mixin", "GeneralAction -> CAPTURE (battle minimized, preparing ball)");
                } else {
                    AutoQiqiClient.log("Mixin", "GeneralAction -> CAPTURE but getBattle() is null!");
                }
                engine.prepareBallThrow();
            }
        }
    }
}
