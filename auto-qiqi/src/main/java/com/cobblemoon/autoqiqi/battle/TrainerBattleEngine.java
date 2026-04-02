package com.cobblemoon.autoqiqi.battle;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection.SwitchTile;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemoon.autoqiqi.AutoQiqiClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Smart battle engine for hard trainer fights (Lv.100 etc.).
 * <ul>
 *   <li>Picks the highest-damage move using type effectiveness, STAB, and base power; ignores moves with 0 PP.</li>
 *   <li>Switches only when forced (fainted) or when the current Pokemon has only ineffective attacks.</li>
 *   <li>When switching, picks the Pokemon with the strongest attack vs the opponent.</li>
 *   <li>Advisor shows "better option" only when current is fainted or has only ineffective moves.</li>
 *   <li>When active Pokemon HP is below 40%, prefers recovery moves (Recover/Soin, Roost/Atterrissage) if available.</li>
 * </ul>
 */
public class TrainerBattleEngine {
    private static final TrainerBattleEngine INSTANCE = new TrainerBattleEngine();

    /** Normalized (lowercase, no spaces) move names that restore HP. Use recovery when HP &lt; 40%. */
    private static final Set<String> RECOVERY_MOVE_NAMES = Set.of(
            "recover", "soin",
            "roost", "atterrissage"
    );

    /** Normalized move names with positive priority (+1 or higher). Used when Cobblemon template does not expose priority. */
    private static final Set<String> PRIORITY_MOVE_NAMES = Set.of(
            "suckerpunch", "quickattack", "aquajet", "bulletpunch", "iceshard", "machpunch",
            "vacuumwave", "watershuriken", "extremespeed", "accelerock", "shadowsneak", "firstimpression"
    );

    /** After this many attacks on the same opponent without KO, advise/auto-switch. */
    public static final int ATTACKS_BEFORE_SWITCH_ADVICE = 5;

    /** Species where we don't trust type info and cycle through our moves instead. */
    private static boolean shouldCycleMoves(String speciesName) {
        if (speciesName == null) return false;
        String lower = speciesName.toLowerCase();
        return lower.startsWith("rotom") || lower.equals("motisma") || lower.equals("heatran");
    }

    private boolean justSwitched = false;
    private int turnCount = 0;
    /** Opponent identity key (internal name); when it changes, attack count resets. */
    private String lastOpponentKey = "";
    /** Number of attacks we've used against the current opponent (same lastOpponentKey). */
    private int attacksAgainstCurrentOpponent = 0;

    private TrainerBattleEngine() {}

    public static TrainerBattleEngine get() { return INSTANCE; }

    public void resetBattle() {
        justSwitched = false;
        turnCount = 0;
        lastOpponentKey = "";
        attacksAgainstCurrentOpponent = 0;
    }

    /**
     * Call at start of each turn (general action). Resets attack count if the opponent changed.
     */
    public void syncOpponentAndAttackCount() {
        String key = getOpponentPokemonNameInternal();
        if (key == null) key = "";
        if (!key.equals(lastOpponentKey)) {
            lastOpponentKey = key;
            attacksAgainstCurrentOpponent = 0;
        }
    }

    /** Call when we have chosen to attack (picked a move) this turn. */
    public void recordAttackAgainstCurrentOpponent() {
        attacksAgainstCurrentOpponent++;
    }

    /** True when we've attacked the current opponent 5+ times without KO (advise or auto-switch). */
    public boolean shouldSwitchAfterManyAttacks() {
        return attacksAgainstCurrentOpponent >= ATTACKS_BEFORE_SWITCH_ADVICE;
    }

    /** Call when we have chosen to switch (forced or voluntary). Resets attack count and marks that the next FIGHT will be the first attack after switch (for priority-move preference). */
    public void resetAttackCountForNewPokemon() {
        attacksAgainstCurrentOpponent = 0;
        justSwitched = true;
    }

    // ========================
    // General action decision
    // ========================

    public enum GeneralChoice { FIGHT, SWITCH }

    /**
     * Decide whether to fight or switch.
     *
     * @param forceSwitch true when the game forces a switch (fainted Pokemon)
     * @param trapped     true when the active Pokemon cannot switch out
     */
    public GeneralChoice decideGeneralAction(boolean forceSwitch, boolean trapped) {
        turnCount++;
        syncOpponentAndAttackCount();

        if (forceSwitch) {
            AutoQiqiClient.logDebug("Trainer", "Decision: SWITCH (forced)");
            return GeneralChoice.SWITCH;
        }
        if (trapped) {
            AutoQiqiClient.logDebug("Trainer", "Decision: FIGHT (trapped)");
            return GeneralChoice.FIGHT;
        }

        float myHp = getActiveHpPercent();
        List<String> oppTypes = getOpponentTypes();
        List<String> myTypes = getActiveTypes();
        String myName = getActivePokemonName();
        String oppName = getOpponentPokemonName();
        Pokemon active = getActivePokemonFromParty(getActivePokemonNameInternal());

        // Voluntary switch after 5 attacks on same opponent without KO
        if (shouldSwitchAfterManyAttacks()) {
            if (!hasOtherSwitchablePokemon()) {
                AutoQiqiClient.logDebug("Trainer", "Decision: FIGHT (only one Pokemon left, cannot switch; " + attacksAgainstCurrentOpponent + " attacks vs " + oppName + ")");
                return GeneralChoice.FIGHT;
            }
            AutoQiqiClient.logDebug("Trainer", "Decision: SWITCH (" + attacksAgainstCurrentOpponent + " attacks vs " + oppName + ", no KO)");
            return GeneralChoice.SWITCH;
        }

        // Voluntary switch only when current has only ineffective attacks (or no damaging moves)
        String oppSpecies = getOpponentPokemonNameInternal();
        if (active != null && !hasEffectiveMoveAgainst(active, oppTypes, oppSpecies)) {
            if (!hasOtherSwitchablePokemon()) {
                AutoQiqiClient.logDebug("Trainer", "Decision: FIGHT (only one Pokemon left, cannot switch; ineffective moves vs " + oppName + ")");
                return GeneralChoice.FIGHT;
            }
            AutoQiqiClient.logDebug("Trainer", "Decision: SWITCH (current has only ineffective moves vs " + oppName + ")");
            return GeneralChoice.SWITCH;
        }

        double oppAdv = TypeChart.getBestStabEffectiveness(oppTypes, myTypes);

        AutoQiqiClient.logDebug("Trainer", "Turn " + turnCount + ": " + myName
                + " HP=" + f(myHp) + "% vs " + oppName + " " + oppTypes
                + " | oppAdv=" + oppAdv + " justSwitched=" + justSwitched);

        AutoQiqiClient.logDebug("Trainer", "Decision: FIGHT (HP=" + f(myHp) + "%)");
        return GeneralChoice.FIGHT;
    }

    // ========================
    // Move selection
    // ========================

    /**
     * Pick the move that will deal the most estimated damage.
     * In BERSERK mode, only considers moves that guarantee a KO (min damage >= opponent current HP).
     * Score = basePower × typeEffectiveness × STAB.
     */
    public MoveTile chooseBestMove(List<MoveTile> tiles) {
        List<String> oppTypes = getOpponentTypes();
        List<String> myTypes = getActiveTypes();
        String oppSpecies = getOpponentPokemonNameInternal();
        List<String> oppTypesForCalc = shouldCycleMoves(oppSpecies) ? List.of() : oppTypes;

        // In Berserk mode: only use moves that kill the target for sure (account for 85% damage roll)
        if (AutoQiqiClient.getBattleMode() == BattleMode.BERSERK) {
            int oppCurrentHp = getOpponentCurrentHp();
            Pokemon active = getActivePokemonFromParty(getActivePokemonNameInternal());
            ClientBattlePokemon opp = getOpponentBattlePokemon();
            if (active != null && opp != null && oppCurrentHp > 0) {
                List<MoveTile> guaranteedKo = new ArrayList<>();
                for (MoveTile tile : tiles) {
                    if (getMoveCurrentPp(tile) == 0) continue;
                    MoveTemplate tpl = lookupMoveTemplate(tile.getMove().getMove());
                    if (tpl == null || tpl.getPower() <= 0) continue;
                    DamageCalculator.DamageRange range = DamageCalculator.computeMoveDamage(
                            active, opp, tpl, oppTypesForCalc, myTypes, oppSpecies);
                    if (range != null && range.isValid() && range.minDamage() >= oppCurrentHp) {
                        guaranteedKo.add(tile);
                    }
                }
                if (!guaranteedKo.isEmpty()) {
                    AutoQiqiClient.logDebug("Trainer", "Berserk: " + guaranteedKo.size()
                            + " move(s) guarantee KO (opp HP=" + oppCurrentHp + "), restricting to those");
                    tiles = guaranteedKo;
                } else {
                    AutoQiqiClient.logDebug("Trainer", "Berserk: no move guarantees KO (opp HP=" + oppCurrentHp + "), using best damage");
                }
            }
        }

        // Filter out moves with 0 PP (unknown PP -1 is treated as usable)
        List<MoveTile> withPp = tiles.stream().filter(t -> getMoveCurrentPp(t) != 0).toList();
        if (withPp.isEmpty() && !tiles.isEmpty()) {
            withPp = tiles; // fallback: all reported 0 PP, pick first (e.g. Struggle)
        }
        if (withPp.isEmpty()) {
            return tiles.isEmpty() ? null : tiles.get(0);
        }
        tiles = withPp;

        // When HP below 40%, prefer recovery moves (Soin/Recover, Atterrissage/Roost)
        float myHp = getActiveHpPercent();
        if (myHp >= 0 && myHp < 40) {
            for (MoveTile tile : tiles) {
                if (isRecoveryMove(tile.getMove().getMove()) && getMoveCurrentPp(tile) != 0) {
                    AutoQiqiClient.logDebug("Trainer", "HP=" + f(myHp) + "% < 40%: using recovery move " + tile.getMove().getMove());
                    return tile;
                }
            }
        }

        // Rotom/Motisma/Heatran: don't trust type info — cycle through current Pokemon's damaging moves
        if (shouldCycleMoves(oppSpecies)) {
            List<MoveTile> damaging = new ArrayList<>();
            for (MoveTile tile : tiles) {
                if (getMoveCurrentPp(tile) == 0) continue;
                MoveInfo info = lookupMove(tile.getMove().getMove());
                if (info != null && info.power() > 0) damaging.add(tile);
            }
            if (!damaging.isEmpty()) {
                damaging.sort(Comparator.comparing(t -> t.getMove().getMove().toLowerCase()));
                int idx = attacksAgainstCurrentOpponent % damaging.size();
                MoveTile chosen = damaging.get(idx);
                AutoQiqiClient.logDebug("Trainer", "CycleMoves: cycling move " + (idx + 1) + "/" + damaging.size() + " -> " + chosen.getMove().getMove());
                return chosen;
            }
        }

        MoveTile best = null;
        double bestScore = -1;
        MoveInfo bestInfo = null;
        List<MoveTile> candidatesWithScore = new ArrayList<>();

        for (MoveTile tile : tiles) {
            if (getMoveCurrentPp(tile) == 0) {
                AutoQiqiClient.logDebug("Trainer", "  Move " + tile.getMove().getMove() + " skipped (0 PP)");
                continue;
            }
            String moveName = tile.getMove().getMove();
            MoveInfo info = lookupMove(moveName);

            double eff = TypeChart.getEffectiveness(info.type, oppTypesForCalc, oppSpecies);
            double stab = myTypes.contains(info.type) ? 1.5 : 1.0;
            double score;

            if (info.power > 0) {
                score = info.power * eff * stab;
            } else {
                score = 5.0;
            }

            AutoQiqiClient.logDebug("Trainer", "  Move " + moveName
                    + " type=" + info.type + " pow=" + info.power + " prio=" + info.priority
                    + " eff=" + eff + " stab=" + stab + " => score=" + f(score));

            candidatesWithScore.add(tile);
            if (score > bestScore) {
                bestScore = score;
                best = tile;
                bestInfo = info;
            }
        }

        // First attack after switch: prefer highest-priority move among damaging moves (e.g. Coup Bas/Sucker Punch for Shifours), only if not resisted
        if (justSwitched && best != null && bestInfo != null && !candidatesWithScore.isEmpty()) {
            MoveTile priorityPick = null;
            int bestPriority = Integer.MIN_VALUE;
            double bestScoreAmongPriority = -1;
            for (MoveTile tile : candidatesWithScore) {
                if (getMoveCurrentPp(tile) == 0) continue;
                MoveInfo info = lookupMove(tile.getMove().getMove());
                if (info.power <= 0) continue;
                double eff = TypeChart.getEffectiveness(info.type, oppTypesForCalc, oppSpecies);
                if (eff < 1.0) continue; // Only consider priority move if not resisted
                double stab = myTypes.contains(info.type) ? 1.5 : 1.0;
                double score = info.power * eff * stab;
                if (info.priority > bestPriority || (info.priority == bestPriority && score > bestScoreAmongPriority)) {
                    bestPriority = info.priority;
                    bestScoreAmongPriority = score;
                    priorityPick = tile;
                }
            }
            if (priorityPick != null && bestPriority > 0) {
                AutoQiqiClient.logDebug("Trainer", "First attack after switch: preferring priority move " + priorityPick.getMove().getMove() + " (priority=" + bestPriority + ")");
                best = priorityPick;
            }
            justSwitched = false;
        }

        if (best != null) {
            AutoQiqiClient.logDebug("Trainer", "Selected move: " + best.getMove().getMove()
                    + " (score=" + f(bestScore) + ")");
        }
        return best != null ? best : (tiles.isEmpty() ? null : tiles.get(0));
    }

    // ========================
    // Switch selection
    // ========================

    /**
     * Pick the switch-in that balances offense, survivability, and speed.
     * Score = (offensive + defensive * 50) * hpFactor * survivalFactor * speedFactor.
     * <ul>
     *   <li>Defensive = 1 / opponent's best STAB effectiveness vs this Pokemon.</li>
     *   <li>SurvivalFactor: heavily penalizes candidates the opponent can likely OHKO (2x/4x super effective).</li>
     *   <li>SpeedFactor: rewards fast candidates with strong attacks; penalizes slow ones facing super effective hits.</li>
     * </ul>
     * Fainted Pokemon are excluded via hpFactor.
     */
    public SwitchTile chooseBestSwitch(List<SwitchTile> tiles) {
        List<String> oppTypes = getOpponentTypes();
        String oppSpecies = getOpponentPokemonNameInternal();
        int oppSpeed = getOpponentSpeed();

        SwitchTile best = null;
        double bestScore = -1;

        for (SwitchTile tile : tiles) {
            Pokemon pokemon = tile.getPokemon();
            float hpPct = getPokemonHpPercent(pokemon);
            if (hpPct <= 0) continue; // Skip fainted

            double hpFactor = hpPct / 100.0;
            double offensive = evaluateOffensivePotential(pokemon, oppTypes, oppSpecies);
            List<String> pokTypes = extractTypes(pokemon);
            String candidateSpecies = pokemon.getSpecies().getName();
            double oppEffAgainstMe = TypeChart.getBestStabEffectiveness(oppTypes, pokTypes, candidateSpecies);
            double defensive = 1.0 / Math.max(0.25, oppEffAgainstMe);

            // Survivability: penalize candidates the opponent can likely OHKO
            double survivalFactor = 1.0;
            if (oppEffAgainstMe >= 4.0) {
                survivalFactor = 0.05; // double super effective = almost certain OHKO
            } else if (oppEffAgainstMe >= 2.0) {
                survivalFactor = 0.3;  // super effective = high risk of OHKO
            }

            // Speed: reward being faster with strong offense; penalize being slower under threat
            int candidateSpeed = DamageCalculator.getStat(pokemon, DamageCalculator.Stat.SPEED);
            boolean isFaster = candidateSpeed > oppSpeed;
            boolean hasSuperEffective = offensive > 150; // power*eff*stab threshold for strong hit

            double speedFactor = 1.0;
            if (isFaster && hasSuperEffective) {
                speedFactor = 1.5; // fast + strong = great switch-in
            } else if (!isFaster && oppEffAgainstMe >= 2.0) {
                speedFactor = 0.5; // slow + takes super effective = bad switch-in
            }

            double score = (offensive + defensive * 50.0) * hpFactor * survivalFactor * speedFactor;

            String name = candidateSpecies;
            AutoQiqiClient.logDebug("Trainer", "  Switch " + name
                    + " types=" + pokTypes + " HP=" + f(hpPct) + "%"
                    + " off=" + f(offensive) + " oppEff=" + f(oppEffAgainstMe) + " def=" + f(defensive)
                    + " surv=" + f(survivalFactor) + " spd=" + candidateSpeed + (isFaster ? ">" : "<=") + oppSpeed
                    + " spdF=" + f(speedFactor)
                    + " => score=" + f(score));

            if (score > bestScore) {
                bestScore = score;
                best = tile;
            }
        }

        if (best != null) {
            AutoQiqiClient.logDebug("Trainer", "Switch to: " + best.getPokemon().getSpecies().getName()
                    + " (off+def+surv+spd, score=" + f(bestScore) + ")");
        }
        return best != null ? best : (tiles.isEmpty() ? null : tiles.get(0));
    }

    // ========================
    // Move lookup via Cobblemon registry
    // ========================

    private record MoveInfo(String type, double power, int priority) {}

    private MoveInfo lookupMove(String moveName) {
        MoveTemplate tpl = lookupMoveTemplate(moveName);
        if (tpl != null) {
            String type = tpl.getElementalType().getName().toLowerCase();
            double power = tpl.getPower();
            int priority = getMovePriorityFromTemplate(tpl);
            if (priority == 0) {
                String normalized = moveName.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (PRIORITY_MOVE_NAMES.contains(normalized)) priority = 1;
            }
            return new MoveInfo(type, power, priority);
        }
        AutoQiqiClient.logDebug("Trainer", "Move not found in registry: '" + moveName + "'");
        return new MoveInfo("normal", 50, 0);
    }

    /** Read move priority from Cobblemon MoveTemplate (e.g. Sucker Punch +1). Uses reflection for API compatibility. */
    private static int getMovePriorityFromTemplate(MoveTemplate tpl) {
        if (tpl == null) return 0;
        try {
            var m = tpl.getClass().getMethod("getPriority");
            Object v = m.invoke(tpl);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Exception ignored) {}
        return 0;
    }

    private MoveTemplate lookupMoveTemplate(String moveName) {
        try {
            String normalized = moveName.toLowerCase().replaceAll("[^a-z0-9]", "");
            MoveTemplate tpl = Moves.INSTANCE.getByName(normalized);
            if (tpl == null) tpl = Moves.INSTANCE.getByName(moveName);
            return tpl;
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the move is a known recovery move (Recover/Soin, Roost/Atterrissage). */
    private static boolean isRecoveryMove(String moveName) {
        if (moveName == null) return false;
        String normalized = moveName.toLowerCase().replaceAll("[^a-z0-9]", "");
        return RECOVERY_MOVE_NAMES.contains(normalized);
    }

    /**
     * Current PP for the move in this tile (battle state). Returns -1 if unknown (do not skip), 0 if out of PP.
     * Uses reflection for Cobblemon battle move API.
     */
    private int getMoveCurrentPp(MoveTile tile) {
        if (tile == null) return -1;
        try {
            Object move = tile.getMove();
            if (move == null) return -1;
            Class<?> c = move.getClass();
            for (String methodName : new String[]{"getCurrentPp", "getPp", "getRemainingPp"}) {
                try {
                    var m = c.getMethod(methodName);
                    Object val = m.invoke(move);
                    if (val instanceof Number n) return n.intValue();
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * True if the Pokemon has at least one damaging move that is effective vs the opponent
     * (effectiveness &gt; 0.5; 0.5 "not very effective" counts as ineffective).
     * Uses opponent species for overrides (e.g. Heatran immune to Fire).
     * For cycle-moves species we don't trust type info: true if the Pokemon has any damaging move.
     */
    @SuppressWarnings("unchecked")
    private boolean hasEffectiveMoveAgainst(Pokemon pokemon, List<String> oppTypes, String oppSpeciesName) {
        try {
            Object moveSetObj = pokemon.getClass().getMethod("getMoveSet").invoke(pokemon);
            java.util.List<?> moves = (java.util.List<?>) moveSetObj.getClass()
                    .getMethod("getMoves").invoke(moveSetObj);
            for (Object moveObj : moves) {
                if (moveObj == null) continue;
                try {
                    MoveTemplate tpl = (MoveTemplate) moveObj.getClass()
                            .getMethod("getTemplate").invoke(moveObj);
                    if (tpl.getPower() <= 0) continue;
                    if (shouldCycleMoves(oppSpeciesName)) return true;
                    String mType = tpl.getElementalType().getName().toLowerCase();
                    double eff = TypeChart.getEffectiveness(mType, oppTypes, oppSpeciesName);
                    if (eff > 0.5) return true;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Evaluate how much damage a Pokemon's moves can deal to the opponent.
     * Returns the best single-move score (power × effectiveness × STAB).
     * Uses opponent species for overrides (e.g. Heatran immune to Fire).
     * For cycle-moves species we don't trust type info: use effectiveness 1.0 (power × STAB only).
     */
    @SuppressWarnings("unchecked")
    private double evaluateOffensivePotential(Pokemon pokemon, List<String> oppTypes, String oppSpeciesName) {
        List<String> pokTypes = extractTypes(pokemon);
        double best = 0;
        boolean ignoreTypes = shouldCycleMoves(oppSpeciesName);
        try {
            // Reflection to bypass MoveSet's KMappedMarker (Kotlin marker inaccessible from Java)
            Object moveSetObj = pokemon.getClass().getMethod("getMoveSet").invoke(pokemon);
            java.util.List<?> moves = (java.util.List<?>) moveSetObj.getClass()
                    .getMethod("getMoves").invoke(moveSetObj);
            for (Object moveObj : moves) {
                if (moveObj == null) continue;
                try {
                    MoveTemplate tpl = (MoveTemplate) moveObj.getClass()
                            .getMethod("getTemplate").invoke(moveObj);
                    String mType = tpl.getElementalType().getName().toLowerCase();
                    double pow = tpl.getPower();
                    if (pow <= 0) continue;
                    double eff = ignoreTypes ? 1.0 : TypeChart.getEffectiveness(mType, oppTypes, oppSpeciesName);
                    double stab = pokTypes.contains(mType) ? 1.5 : 1.0;
                    double score = pow * eff * stab;
                    if (score > best) best = score;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (!ignoreTypes) {
                best = TypeChart.getBestStabEffectiveness(pokTypes, oppTypes, oppSpeciesName) * 80;
            } else if (best == 0) {
                best = 80;
            }
        }
        return best;
    }

    // ========================
    // Battle state reading
    // ========================

    private float getActiveHpPercent() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return -1;
            var actors = battle.getSide1().getActors();
            if (actors.isEmpty()) return -1;
            var actives = actors.get(0).getActivePokemon();
            if (actives.isEmpty()) return -1;
            ClientBattlePokemon bp = actives.get(0).getBattlePokemon();
            if (bp == null) return -1;
            return (float) bp.getHpValue() / bp.getMaxHp() * 100f;
        } catch (Exception e) { return -1; }
    }

    /** Internal name (English) for cache and matching. */
    private String getActivePokemonNameInternal() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return "?";
            var bp = battle.getSide1().getActors().get(0).getActivePokemon().get(0).getBattlePokemon();
            return bp != null ? bp.getSpecies().getName() : "?";
        } catch (Exception e) { return "?"; }
    }

    /** Display name (localized, e.g. French) for HUD. */
    private String getActivePokemonName() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return "?";
            var bp = battle.getSide1().getActors().get(0).getActivePokemon().get(0).getBattlePokemon();
            return bp != null ? getSpeciesDisplayName(bp.getSpecies()) : "?";
        } catch (Exception e) { return "?"; }
    }

    private List<String> getActiveTypes() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return List.of();
            var bp = battle.getSide1().getActors().get(0).getActivePokemon().get(0).getBattlePokemon();
            if (bp == null) return List.of();
            return extractTypesFromSpecies(bp.getSpecies());
        } catch (Exception e) { return List.of(); }
    }

    /** Internal name (English) for cache. */
    private String getOpponentPokemonNameInternal() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return "?";
            var bp = battle.getSide2().getActors().get(0).getActivePokemon().get(0).getBattlePokemon();
            return bp != null ? bp.getSpecies().getName() : "?";
        } catch (Exception e) { return "?"; }
    }

    /** Display name (localized, e.g. French) for HUD. */
    private String getOpponentPokemonName() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return "?";
            var bp = battle.getSide2().getActors().get(0).getActivePokemon().get(0).getBattlePokemon();
            return bp != null ? getSpeciesDisplayName(bp.getSpecies()) : "?";
        } catch (Exception e) { return "?"; }
    }

    /** Opponent's speed stat (for switch-in speed comparison). */
    private int getOpponentSpeed() {
        ClientBattlePokemon bp = getOpponentBattlePokemon();
        if (bp == null) return 100; // fallback
        return DamageCalculator.getStat(bp, DamageCalculator.Stat.SPEED);
    }

    /** Opponent's current HP (for Berserk guaranteed-KO check). */
    private int getOpponentCurrentHp() {
        try {
            ClientBattlePokemon bp = getOpponentBattlePokemon();
            if (bp == null) return 0;
            double v = bp.getHpValue();
            return v > 0 && v <= Integer.MAX_VALUE ? (int) Math.ceil(v) : 0;
        } catch (Exception e) { return 0; }
    }

    /** Opponent's active battle Pokémon (for damage calc). */
    private ClientBattlePokemon getOpponentBattlePokemon() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return null;
            var actives = battle.getSide2().getActors().get(0).getActivePokemon();
            if (actives.isEmpty()) return null;
            return actives.get(0).getBattlePokemon();
        } catch (Exception e) { return null; }
    }

    /**
     * True if there is at least one other non-fainted Pokemon in the party (so we can switch).
     * When only one Pokemon is left (e.g. Sylveon vs Yveltal), voluntary switch must not be chosen.
     * Public so the decision router can use it for BERSERK/ROAMING.
     */
    public boolean hasOtherSwitchablePokemon() {
        var party = CobblemonClient.INSTANCE.getStorage().getParty();
        if (party == null) return false;
        try {
            Object partyObj = (Object) party;
            @SuppressWarnings("unchecked")
            java.util.List<Pokemon> slots = (java.util.List<Pokemon>) partyObj.getClass().getMethod("getSlots").invoke(partyObj);
            int nonFainted = 0;
            for (Pokemon p : slots) {
                if (p != null && p.getCurrentHealth() > 0) nonFainted++;
            }
            return nonFainted >= 2;
        } catch (Exception ignored) {}
        return false;
    }

    /** Our active Pokémon from party (by internal name match). */
    private Pokemon getActivePokemonFromParty(String activeNameInternal) {
        if (activeNameInternal == null) return null;
        var party = CobblemonClient.INSTANCE.getStorage().getParty();
        if (party == null) return null;
        try {
            Object partyObj = (Object) party;
            @SuppressWarnings("unchecked")
            java.util.List<Pokemon> slots = (java.util.List<Pokemon>) partyObj.getClass().getMethod("getSlots").invoke(partyObj);
            for (Pokemon p : slots) {
                if (p != null && p.getCurrentHealth() > 0
                        && activeNameInternal.equalsIgnoreCase(p.getSpecies().getName())) {
                    return p;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> getOpponentTypes() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return List.of();
            var bp = battle.getSide2().getActors().get(0).getActivePokemon().get(0).getBattlePokemon();
            if (bp == null) return List.of();
            return extractTypesFromSpecies(bp.getSpecies());
        } catch (Exception e) { return List.of(); }
    }

    // ========================
    // Type extraction helpers
    // ========================

    private List<String> extractTypesFromSpecies(com.cobblemon.mod.common.pokemon.Species species) {
        List<String> types = new ArrayList<>();
        try {
            var primary = species.getPrimaryType();
            if (primary != null) types.add(primary.getName().toLowerCase());
            var secondary = species.getSecondaryType();
            if (secondary != null) types.add(secondary.getName().toLowerCase());
        } catch (Exception e) {
            AutoQiqiClient.logDebug("Trainer", "Failed to extract types for " + species.getName());
        }
        return types;
    }

    private List<String> extractTypes(Pokemon pokemon) {
        try {
            return extractTypesFromSpecies(pokemon.getSpecies());
        } catch (Exception e) { return List.of(); }
    }

    /** Localized display name for the current game language (e.g. French). */
    private static String getSpeciesDisplayName(com.cobblemon.mod.common.pokemon.Species species) {
        if (species == null) return "?";
        try {
            var text = species.getTranslatedName();
            return text != null ? text.getString() : species.getName();
        } catch (Exception e) {
            return species.getName();
        }
    }

    private float getPokemonHpPercent(Pokemon pokemon) {
        try {
            int current = pokemon.getCurrentHealth();
            int max = pokemon.getHp();
            if (max <= 0) return 100f;
            return (float) current / max * 100f;
        } catch (Exception e) { return 100f; }
    }

    // ========================
    // Battle Advisor (passive HUD, works in any battle)
    // ========================

    public record AdvisorInfo(
            String opponentName,
            String opponentTypesDisplay,
            String currentName,
            double currentScore,
            String bestName,
            String bestTypesDisplay,
            double bestScore,
            double bestEffectiveness,
            boolean hasBetterOption,
            String damageRangePercent,
            String bestMoveName,
            boolean adviseSwitchAfterAttacks
    ) {}

    private String advisorCacheKey = "";
    private AdvisorInfo cachedAdvisor = null;
    private long advisorCacheTimeMs = 0;
    private static final long ADVISOR_CACHE_TTL_MS = 500;

    /**
     * Returns advisor info for the current battle, or null if not in battle.
     * Cached to avoid recomputing every render frame.
     */
    public AdvisorInfo getAdvisorInfo() {
        long now = System.currentTimeMillis();
        if (now - advisorCacheTimeMs < ADVISOR_CACHE_TTL_MS && cachedAdvisor != null) {
            return cachedAdvisor;
        }

        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) {
                cachedAdvisor = null;
                advisorCacheTimeMs = now;
                return null;
            }

            String oppDisplay = getOpponentPokemonName();
            String myDisplay = getActivePokemonName();
            String cacheKey = getOpponentPokemonNameInternal() + "|" + getActivePokemonNameInternal();

            if (cacheKey.equals(advisorCacheKey) && cachedAdvisor != null) {
                advisorCacheTimeMs = now;
                return cachedAdvisor;
            }

            cachedAdvisor = computeAdvisor(oppDisplay, myDisplay, getActivePokemonNameInternal());
            advisorCacheKey = cacheKey;
            advisorCacheTimeMs = now;
            return cachedAdvisor;
        } catch (Exception e) {
            cachedAdvisor = null;
            advisorCacheTimeMs = now;
            return null;
        }
    }

    /** Force cache refresh (e.g. when opponent switches). */
    public void invalidateAdvisorCache() {
        advisorCacheKey = "";
        cachedAdvisor = null;
    }

    private AdvisorInfo computeAdvisor(String oppDisplay, String activeDisplay, String activeNameInternal) {
        List<String> oppTypes = getOpponentTypes();
        if (oppTypes.isEmpty()) return null;
        String oppSpecies = getOpponentPokemonNameInternal();

        var party = CobblemonClient.INSTANCE.getStorage().getParty();
        if (party == null) return null;

        String bestNameDisplay = null;
        com.cobblemon.mod.common.pokemon.Species bestSpecies = null;
        List<String> bestTypes = List.of();
        double bestScore = -1;
        double bestEff = 1.0;
        double activeScore = -1;

        @SuppressWarnings("unchecked")
        java.util.List<Pokemon> slots;
        try {
            Object partyObj = (Object) party;
            slots = (java.util.List<Pokemon>) partyObj.getClass().getMethod("getSlots").invoke(partyObj);
        } catch (Exception e) {
            return null;
        }
        for (Pokemon p : slots) {
            if (p == null) continue;
            if (p.getCurrentHealth() <= 0) continue;

            List<String> pTypes = extractTypes(p);
            double offensive = evaluateOffensivePotential(p, oppTypes, oppSpecies);
            double oppEffAgainstMe = TypeChart.getBestStabEffectiveness(oppTypes, pTypes);
            double defensive = 1.0 / Math.max(0.25, oppEffAgainstMe);
            float hpPct = getPokemonHpPercent(p);
            double hpFactor = hpPct > 0 ? hpPct / 100.0 : 1.0;

            double score = (offensive + defensive * 50.0) * hpFactor;

            String nameInternal = p.getSpecies().getName();
            if (nameInternal.equalsIgnoreCase(activeNameInternal)) {
                activeScore = score;
            }

            if (score > bestScore) {
                bestScore = score;
                bestNameDisplay = getSpeciesDisplayName(p.getSpecies());
                bestSpecies = p.getSpecies();
                bestTypes = pTypes;
                bestEff = TypeChart.getBestStabEffectiveness(pTypes, oppTypes, oppSpecies);
            }
        }

        if (bestNameDisplay == null || bestSpecies == null) return null;

        // Advise switch only when current is fainted or has only ineffective moves (no "better matchup" suggestion)
        boolean activeIsFainted = (activeScore < 0);
        boolean isSamePokemon = bestSpecies.getName().equalsIgnoreCase(activeNameInternal);
        Pokemon activePokemonForAdvice = getActivePokemonFromParty(activeNameInternal);
        boolean currentHasOnlyIneffective = activePokemonForAdvice != null
                && !hasEffectiveMoveAgainst(activePokemonForAdvice, oppTypes, oppSpecies);
        boolean hasBetter = activeIsFainted
                || (currentHasOnlyIneffective && !isSamePokemon && bestScore > 0);

        // Official damage range (% of opponent's HP) and name for current active's best move
        String damageRangePercent = null;
        String bestMoveName = null;
        Pokemon activePokemon = activePokemonForAdvice;
        ClientBattlePokemon opponentBp = getOpponentBattlePokemon();
        List<String> oppTypesForAdvice = shouldCycleMoves(oppSpecies) ? List.of() : oppTypes;
        if (activePokemon != null && opponentBp != null) {
            DamageCalculator.DamageRange range = DamageCalculator.computeBestMoveDamage(
                    activePokemon, opponentBp, oppTypesForAdvice, getActiveTypes(), oppSpecies);
            if (range != null && range.isValid()) {
                damageRangePercent = range.formatPercentRange();
                if (range.moveName() != null && !range.moveName().isEmpty()) {
                    bestMoveName = range.moveName();
                }
            }
        }

        boolean adviseSwitch = attacksAgainstCurrentOpponent >= ATTACKS_BEFORE_SWITCH_ADVICE;

        return new AdvisorInfo(
                oppDisplay,
                formatTypes(oppTypes),
                activeDisplay,
                activeScore,
                bestNameDisplay,
                formatTypes(bestTypes),
                bestScore,
                bestEff,
                hasBetter,
                damageRangePercent,
                bestMoveName,
                adviseSwitch
        );
    }

    private static String formatTypes(List<String> types) {
        if (types.isEmpty()) return "?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append("/");
            String t = types.get(i);
            sb.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1));
        }
        return sb.toString();
    }

    private static String f(double v) { return String.format("%.1f", v); }
    private static String f(float v) { return String.format("%.1f", v); }
}
