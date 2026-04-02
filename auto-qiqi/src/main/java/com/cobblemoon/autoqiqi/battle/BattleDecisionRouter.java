package com.cobblemoon.autoqiqi.battle;

import java.util.List;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection.SwitchTile;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
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

        TrainerBattleEngine.get().syncOpponentAndAttackCount();

        BattleMode mode = AutoBattleEngine.get().getMode();
        TrainerBattleEngine.GeneralChoice choice;

        if (mode == BattleMode.TRAINER) {
            AutoQiqiClient.logDebug("Mixin", "GeneralAction: Trainer mode, auto-fighting");
            choice = TrainerBattleEngine.get().decideGeneralAction(forceSwitch, trapped);
        } else if (mode == BattleMode.BERSERK) {
            // BERSERK: always fight, never switch
            choice = TrainerBattleEngine.GeneralChoice.FIGHT;
        } else {
            // ROAMING: random FIGHT/SWITCH
            if (trapped || Math.random() >= AutoQiqiConfig.get().battleSwitchChance) {
                choice = TrainerBattleEngine.GeneralChoice.FIGHT;
            } else {
                choice = TrainerBattleEngine.GeneralChoice.SWITCH;
            }
        }

        // Never open switch screen when only one Pokemon left (no one to switch to)
        if (choice == TrainerBattleEngine.GeneralChoice.SWITCH && !forceSwitch
                && !TrainerBattleEngine.get().hasOtherSwitchablePokemon()) {
            AutoQiqiClient.logDebug("Mixin", "GeneralAction: only one Pokemon left, forcing FIGHT instead of SWITCH");
            choice = TrainerBattleEngine.GeneralChoice.FIGHT;
        }

        if (choice == TrainerBattleEngine.GeneralChoice.SWITCH) {
            TrainerBattleEngine.get().resetAttackCountForNewPokemon();
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
        MoveTile chosen;
        if (mode == BattleMode.TRAINER || mode == BattleMode.BERSERK) {
            AutoQiqiClient.logDebug("Mixin", "MoveSelection: " + (mode == BattleMode.TRAINER ? "Trainer" : "Berserk") + " mode, " + tiles.size() + " selectable moves");
            chosen = TrainerBattleEngine.get().chooseBestMove(tiles);
        } else {
            chosen = tiles.isEmpty() ? null : tiles.get((int) (Math.random() * tiles.size()));
        }
        if (chosen != null) {
            TrainerBattleEngine.get().recordAttackAgainstCurrentOpponent();
        }
        return chosen;
    }

    /** Per-request guard: never submit twice for the same turn (same request). */
    private static Object lastPerformMoveSelectionRequest = null;
    /** Time guard: safety net in case the same request is seen across ticks (e.g. bug or capture path). */
    private static long lastPerformMoveSelectionTick = -1000;
    private static final int PERFORM_MOVE_SELECTION_DEBOUNCE_TICKS = 10;
    /** Retry timeout: if response is still null after this many ticks, retry the move selection. */
    private static final int MOVE_SELECTION_RETRY_TICKS = 40; // 2 seconds

    /** Clear debounce state when leaving battle (e.g. so next battle is not stuck). */
    public static void clearMoveSelectionDebounce() {
        lastPerformMoveSelectionRequest = null;
        lastPerformMoveSelectionTick = -1000;
    }

    /**
     * Performs move selection for autofight: chooses a move and applies it (onClick or target selection).
     * Call only when not in capture mode and shouldAutoFight. Caller must ensure
     * {@code currentSelection.getRequest().getResponse() == null}.
     * Only the client tick calls this for autofight (single path). Debounced per-request and per-time as a safety net.
     */
    public static void performMoveSelection(BattleMoveSelection currentSelection) {
        if (currentSelection.getRequest().getResponse() != null) return;
        Object request = currentSelection.getRequest();
        long now = AutoQiqiClient.getClientTickCounter();
        if (request == lastPerformMoveSelectionRequest) {
            // Allow retry if response is still null after timeout (onClick may have failed)
            if (now - lastPerformMoveSelectionTick < MOVE_SELECTION_RETRY_TICKS) return;
            AutoQiqiClient.logDebug("Battle", "MoveSelection: retrying (response still null after " + MOVE_SELECTION_RETRY_TICKS + " ticks)");
        }
        if (now - lastPerformMoveSelectionTick < PERFORM_MOVE_SELECTION_DEBOUNCE_TICKS) return;
        lastPerformMoveSelectionTick = now;
        lastPerformMoveSelectionRequest = request;
        List<MoveTile> allTiles = currentSelection.getMoveTiles();
        List<MoveTile> tiles = allTiles.stream()
                .filter(t -> t.getSelectable()).toList();
        MoveTile chosen = chooseMove(tiles);
        // Fallback: if no selectable move was chosen (e.g. all disabled/0 PP) but moves exist, use first to unstick
        if (chosen == null && !allTiles.isEmpty()) {
            AutoQiqiClient.logDebug("Battle", "MoveSelection: no selectable move chosen (selectable=" + tiles.size() + ", total=" + allTiles.size() + "), using first move as fallback");
            chosen = allTiles.get(0);
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (chosen != null) {
            if (currentSelection.getRequest().getActivePokemon().getFormat()
                    .getBattleType().getPokemonPerSide() == 1) {
                chosen.onClick();
            } else {
                currentSelection.playDownSound(client.getSoundManager());
                currentSelection.getBattleGUI().changeActionSelection(
                        new BattleTargetSelection(
                                currentSelection.getBattleGUI(), currentSelection.getRequest(),
                                chosen.getMove(), null, null));
            }
        } else {
            currentSelection.playDownSound(client.getSoundManager());
            currentSelection.getBattleGUI().changeActionSelection(null);
        }
    }

    /**
     * Chooses a switch target. Call only when not in capture mode and shouldAutoFight.
     * TRAINER uses {@link TrainerBattleEngine#chooseBestSwitch}; BERSERK and ROAMING use random.
     */
    public static SwitchTile chooseSwitch(List<SwitchTile> tiles) {
        BattleMode mode = AutoBattleEngine.get().getMode();
        if (mode == BattleMode.TRAINER) {
            AutoQiqiClient.logDebug("Mixin", "SwitchSelection: Trainer mode, " + tiles.size() + " available");
            return TrainerBattleEngine.get().chooseBestSwitch(tiles);
        }
        return tiles.isEmpty() ? null : tiles.get((int) (Math.random() * tiles.size()));
    }
}
