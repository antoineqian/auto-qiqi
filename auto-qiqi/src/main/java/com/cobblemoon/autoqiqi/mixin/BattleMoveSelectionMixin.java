package com.cobblemoon.autoqiqi.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
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
            AutoQiqiClient.logDebug("Mixin", "MoveSelection: CaptureEngine active");
            AutoQiqiClient.runLater(() -> {
                var current = self.getBattleGUI().getCurrentActionSelection();
                if (!(current instanceof BattleMoveSelection currentSelection)) {
                    AutoQiqiClient.logDebug("Mixin", "MoveSelection: no longer on move selection, skipping");
                    return;
                }
                if (currentSelection.getRequest().getResponse() != null) {
                    AutoQiqiClient.logDebug("Mixin", "MoveSelection: response already set, skipping");
                    return;
                }

                List<MoveTile> selectable = currentSelection.getMoveTiles().stream()
                        .filter(t -> t.getSelectable()).toList();

                AutoQiqiClient.logDebug("Mixin", "MoveSelection: " + selectable.size() + " selectable moves: "
                        + selectable.stream().map(t -> t.getMove().getMove()).toList());

                MoveTile chosen = CaptureEngine.get().chooseMoveFromTiles(selectable);
                if (chosen != null) {
                    AutoQiqiClient.logDebug("Mixin", "MoveSelection: clicking move '" + chosen.getMove().getMove() + "'");
                    if (currentSelection.getRequest().getActivePokemon().getFormat()
                            .getBattleType().getPokemonPerSide() == 1) {
                        chosen.onClick();
                    } else {
                        currentSelection.playDownSound(MinecraftClient.getInstance().getSoundManager());
                        currentSelection.getBattleGUI().changeActionSelection(
                                new BattleTargetSelection(
                                        currentSelection.getBattleGUI(), currentSelection.getRequest(),
                                        chosen.getMove(), null, null));
                    }
                } else {
                    // Backed out because we should throw a ball (enough False Swipes or decision was THROW_BALL)
                    var engine = CaptureEngine.get();
                    var battle = CobblemonClient.INSTANCE.getBattle();
                    boolean shouldThrow = (engine.getCurrentAction() == com.cobblemoon.autoqiqi.battle.CaptureAction.THROW_BALL
                            || engine.hasEnoughFalseSwipesForCapture());
                    if (battle != null && shouldThrow) {
                        AutoQiqiClient.logDebug("Mixin", "MoveSelection: switching to ball throw (minimize + prepare)");
                        battle.setMinimised(true);
                        engine.prepareBallThrow();
                    } else {
                        AutoQiqiClient.logDebug("Mixin", "MoveSelection: no valid move found, backing out");
                        currentSelection.playDownSound(MinecraftClient.getInstance().getSoundManager());
                        currentSelection.getBattleGUI().changeActionSelection(null);
                    }
                }
            }, config.battleSelectDelay);
            return;
        }

        // Autofight move selection: do nothing here. Only the client tick calls performMoveSelection (single path, no race when unfocused).
    }
}
