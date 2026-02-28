package com.cobblemoon.autoqiqi.battle;

import java.util.List;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection.SwitchTile;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;

import net.minecraft.client.MinecraftClient;

/**
 * Central router for in-battle decisions when auto-fighting and <em>not</em> in capture mode.
 * <p>
 * The battle GUI mixins branch on {@link CaptureEngine#isActive()} first; when capture is
 * inactive they call this router so all "who decides" logic lives in one place.
 * <ul>
 *   <li><strong>TRAINER</strong>: {@link TrainerBattleEngine} for general action, move, and switch.</li>
 *   <li><strong>BERSERK</strong>: Random (with switch chance) for general action; {@link TrainerBattleEngine}
 *       for move and switch (smart picks to secure KOs).</li>
 *   <li><strong>ROAMING</strong>: Random for general action, move, and switch.</li>
 * </ul>
 */
public final class BattleDecisionRouter {

    private BattleDecisionRouter() {}

    /**
     * Handles the general action (FIGHT vs SWITCH) and updates the battle GUI.
     * Call only when {@link CaptureEngine#isActive()} is false and {@link AutoQiqiClient#shouldAutoFight()} is true.
     */
    public static void handleGeneralAction(BattleGeneralActionSelection selection) {
        MinecraftClient client = MinecraftClient.getInstance();
        selection.playDownSound(client.getSoundManager());

        boolean forceSwitch = selection.getRequest().getForceSwitch();
        boolean trapped = selection.getRequest().getMoveSet() != null
                && selection.getRequest().getMoveSet().getTrapped();

        BattleMode mode = AutoBattleEngine.get().getMode();
        TrainerBattleEngine.GeneralChoice choice;

        if (mode == BattleMode.TRAINER) {
            AutoQiqiClient.log("Mixin", "GeneralAction: Trainer mode, auto-fighting");
            choice = TrainerBattleEngine.get().decideGeneralAction(forceSwitch, trapped);
        } else {
            // BERSERK and ROAMING: random FIGHT/SWITCH (no TrainerBattleEngine for general action)
            if (trapped || Math.random() >= AutoQiqiConfig.get().battleSwitchChance) {
                choice = TrainerBattleEngine.GeneralChoice.FIGHT;
            } else {
                choice = TrainerBattleEngine.GeneralChoice.SWITCH;
            }
        }

        if (choice == TrainerBattleEngine.GeneralChoice.SWITCH) {
            selection.getBattleGUI().changeActionSelection(
                    new BattleSwitchPokemonSelection(selection.getBattleGUI(), selection.getRequest()));
        } else {
            selection.getBattleGUI().changeActionSelection(
                    new BattleMoveSelection(selection.getBattleGUI(), selection.getRequest()));
        }
    }

    /**
     * Chooses a move. Call only when not in capture mode and shouldAutoFight.
     * TRAINER and BERSERK use {@link TrainerBattleEngine#chooseBestMove}; ROAMING uses random.
     */
    public static MoveTile chooseMove(List<MoveTile> tiles) {
        BattleMode mode = AutoBattleEngine.get().getMode();
        if (mode == BattleMode.TRAINER || mode == BattleMode.BERSERK) {
            AutoQiqiClient.log("Mixin", "MoveSelection: " + (mode == BattleMode.TRAINER ? "Trainer" : "Berserk") + " mode, " + tiles.size() + " selectable moves");
            return TrainerBattleEngine.get().chooseBestMove(tiles);
        }
        return tiles.isEmpty() ? null : tiles.get((int) (Math.random() * tiles.size()));
    }

    /**
     * Chooses a switch target. Call only when not in capture mode and shouldAutoFight.
     * TRAINER uses {@link TrainerBattleEngine#chooseBestSwitch}; BERSERK and ROAMING use random.
     */
    public static SwitchTile chooseSwitch(List<SwitchTile> tiles) {
        BattleMode mode = AutoBattleEngine.get().getMode();
        if (mode == BattleMode.TRAINER) {
            AutoQiqiClient.log("Mixin", "SwitchSelection: Trainer mode, " + tiles.size() + " available");
            return TrainerBattleEngine.get().chooseBestSwitch(tiles);
        }
        return tiles.isEmpty() ? null : tiles.get((int) (Math.random() * tiles.size()));
    }
}
