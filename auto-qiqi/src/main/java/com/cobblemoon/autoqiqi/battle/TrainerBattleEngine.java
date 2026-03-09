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
import java.util.List;

/**
 * Smart battle engine for hard trainer fights (Lv.100 etc.).
 * <ul>
 *   <li>Picks the highest-damage move using type effectiveness, STAB, and base power.</li>
 *   <li>Never voluntarily switches — fights with current Pokemon until KO.</li>
 *   <li>When forced to switch (fainted), picks the Pokemon with the strongest attack.</li>
 * </ul>
 */
public class TrainerBattleEngine {
    private static final TrainerBattleEngine INSTANCE = new TrainerBattleEngine();

    private boolean justSwitched = false;
    private int turnCount = 0;

    private TrainerBattleEngine() {}

    public static TrainerBattleEngine get() { return INSTANCE; }

    public void resetBattle() {
        justSwitched = false;
        turnCount = 0;
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

        if (forceSwitch) {
            AutoQiqiClient.log("Trainer", "Decision: SWITCH (forced)");
            return GeneralChoice.SWITCH;
        }
        if (trapped) {
            AutoQiqiClient.log("Trainer", "Decision: FIGHT (trapped)");
            return GeneralChoice.FIGHT;
        }

        float myHp = getActiveHpPercent();
        List<String> oppTypes = getOpponentTypes();
        List<String> myTypes = getActiveTypes();
        String myName = getActivePokemonName();
        String oppName = getOpponentPokemonName();

        double oppAdv = TypeChart.getBestStabEffectiveness(oppTypes, myTypes);

        AutoQiqiClient.log("Trainer", "Turn " + turnCount + ": " + myName
                + " HP=" + f(myHp) + "% vs " + oppName + " " + oppTypes
                + " | oppAdv=" + oppAdv + " justSwitched=" + justSwitched);

        if (justSwitched) {
            justSwitched = false;
        }

        // Never voluntarily switch — fight with current Pokemon until KO
        AutoQiqiClient.log("Trainer", "Decision: FIGHT (no voluntary switch, HP=" + f(myHp) + "%)");
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

        // In Berserk mode: only use moves that kill the target for sure (account for 85% damage roll)
        if (AutoQiqiClient.getBattleMode() == BattleMode.BERSERK) {
            int oppCurrentHp = getOpponentCurrentHp();
            Pokemon active = getActivePokemonFromParty(getActivePokemonNameInternal());
            ClientBattlePokemon opp = getOpponentBattlePokemon();
            if (active != null && opp != null && oppCurrentHp > 0) {
                List<MoveTile> guaranteedKo = new ArrayList<>();
                for (MoveTile tile : tiles) {
                    MoveTemplate tpl = lookupMoveTemplate(tile.getMove().getMove());
                    if (tpl == null || tpl.getPower() <= 0) continue;
                    DamageCalculator.DamageRange range = DamageCalculator.computeMoveDamage(
                            active, opp, tpl, oppTypes, myTypes);
                    if (range != null && range.isValid() && range.minDamage() >= oppCurrentHp) {
                        guaranteedKo.add(tile);
                    }
                }
                if (!guaranteedKo.isEmpty()) {
                    AutoQiqiClient.log("Trainer", "Berserk: " + guaranteedKo.size()
                            + " move(s) guarantee KO (opp HP=" + oppCurrentHp + "), restricting to those");
                    tiles = guaranteedKo;
                } else {
                    AutoQiqiClient.log("Trainer", "Berserk: no move guarantees KO (opp HP=" + oppCurrentHp + "), using best damage");
                }
            }
        }

        MoveTile best = null;
        double bestScore = -1;

        for (MoveTile tile : tiles) {
            String moveName = tile.getMove().getMove();
            MoveInfo info = lookupMove(moveName);

            double eff = TypeChart.getEffectiveness(info.type, oppTypes);
            double stab = myTypes.contains(info.type) ? 1.5 : 1.0;
            double score;

            if (info.power > 0) {
                score = info.power * eff * stab;
            } else {
                score = 5.0;
            }

            AutoQiqiClient.log("Trainer", "  Move " + moveName
                    + " type=" + info.type + " pow=" + info.power
                    + " eff=" + eff + " stab=" + stab + " => score=" + f(score));

            if (score > bestScore) {
                bestScore = score;
                best = tile;
            }
        }

        if (best != null) {
            AutoQiqiClient.log("Trainer", "Selected move: " + best.getMove().getMove()
                    + " (score=" + f(bestScore) + ")");
        }
        return best != null ? best : (tiles.isEmpty() ? null : tiles.get(0));
    }

    // ========================
    // Switch selection
    // ========================

    /**
     * Pick the switch-in with the strongest attack vs the opponent.
     * Score = offensive potential only (no defensive weighting).
     * Fainted Pokemon are excluded via hpFactor.
     */
    public SwitchTile chooseBestSwitch(List<SwitchTile> tiles) {
        List<String> oppTypes = getOpponentTypes();

        SwitchTile best = null;
        double bestScore = -1;

        for (SwitchTile tile : tiles) {
            Pokemon pokemon = tile.getPokemon();
            float hpPct = getPokemonHpPercent(pokemon);
            if (hpPct <= 0) continue; // Skip fainted

            double hpFactor = hpPct / 100.0;
            double offensive = evaluateOffensivePotential(pokemon, oppTypes);
            double score = offensive * hpFactor;

            String name = pokemon.getSpecies().getName();
            List<String> pokTypes = extractTypes(pokemon);
            AutoQiqiClient.log("Trainer", "  Switch " + name
                    + " types=" + pokTypes + " HP=" + f(hpPct) + "%"
                    + " off=" + f(offensive) + " => score=" + f(score));

            if (score > bestScore) {
                bestScore = score;
                best = tile;
            }
        }

        if (best != null) {
            AutoQiqiClient.log("Trainer", "Switch to: " + best.getPokemon().getSpecies().getName()
                    + " (strongest attack, score=" + f(bestScore) + ")");
        }
        return best != null ? best : (tiles.isEmpty() ? null : tiles.get(0));
    }

    // ========================
    // Move lookup via Cobblemon registry
    // ========================

    private record MoveInfo(String type, double power) {}

    private MoveInfo lookupMove(String moveName) {
        MoveTemplate tpl = lookupMoveTemplate(moveName);
        if (tpl != null) {
            String type = tpl.getElementalType().getName().toLowerCase();
            double power = tpl.getPower();
            return new MoveInfo(type, power);
        }
        AutoQiqiClient.log("Trainer", "Move not found in registry: '" + moveName + "'");
        return new MoveInfo("normal", 50);
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

    /**
     * Evaluate how much damage a Pokemon's moves can deal to the opponent.
     * Returns the best single-move score (power × effectiveness × STAB).
     */
    @SuppressWarnings("unchecked")
    private double evaluateOffensivePotential(Pokemon pokemon, List<String> oppTypes) {
        List<String> pokTypes = extractTypes(pokemon);
        double best = 0;
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
                    double eff = TypeChart.getEffectiveness(mType, oppTypes);
                    double stab = pokTypes.contains(mType) ? 1.5 : 1.0;
                    double score = pow * eff * stab;
                    if (score > best) best = score;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            // Fallback: assume STAB coverage with ~80 base power
            best = TypeChart.getBestStabEffectiveness(pokTypes, oppTypes) * 80;
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
            AutoQiqiClient.log("Trainer", "Failed to extract types for " + species.getName());
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
    // Trainer planned action (what trainer mode would do this turn — for HUD)
    // ========================

    /**
     * What the trainer battle engine would do this turn.
     * Computed from current battle state (no GUI tiles). Used to show "Trainer would: FIGHT → Move" in HUD.
     */
    public record TrainerPlannedAction(
            GeneralChoice choice,
            String reason,
            String moveName,
            String moveType,
            double moveScore,
            String switchPokemonName,
            double switchScore
    ) {
        public static TrainerPlannedAction fight(String reason, String moveName, String moveType, double moveScore) {
            return new TrainerPlannedAction(GeneralChoice.FIGHT, reason, moveName, moveType, moveScore, null, -1);
        }
        public static TrainerPlannedAction switchTo(String reason, String switchPokemonName, double switchScore) {
            return new TrainerPlannedAction(GeneralChoice.SWITCH, reason, null, null, -1, switchPokemonName, switchScore);
        }
    }

    private String trainerPlannedCacheKey = "";
    private TrainerPlannedAction cachedPlannedAction = null;
    private long trainerPlannedCacheTimeMs = 0;
    private static final long TRAINER_PLANNED_CACHE_TTL_MS = 400;

    /**
     * Returns what the trainer engine would do this turn (general action + best move or best switch).
     * Cached briefly. Returns null if not in battle.
     */
    public TrainerPlannedAction getTrainerPlannedAction() {
        long now = System.currentTimeMillis();
        if (now - trainerPlannedCacheTimeMs < TRAINER_PLANNED_CACHE_TTL_MS && cachedPlannedAction != null) {
            return cachedPlannedAction;
        }
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) {
                cachedPlannedAction = null;
                trainerPlannedCacheTimeMs = now;
                return null;
            }
            String activeName = getActivePokemonNameInternal();
            float activeHp = getActiveHpPercent();
            String cacheKey = activeName + "|" + activeHp + "|" + getOpponentPokemonNameInternal();
            if (cacheKey.equals(trainerPlannedCacheKey) && cachedPlannedAction != null) {
                trainerPlannedCacheTimeMs = now;
                return cachedPlannedAction;
            }
            cachedPlannedAction = computeTrainerPlannedAction(activeHp);
            trainerPlannedCacheKey = cacheKey;
            trainerPlannedCacheTimeMs = now;
            return cachedPlannedAction;
        } catch (Exception e) {
            cachedPlannedAction = null;
            trainerPlannedCacheTimeMs = now;
            return null;
        }
    }

    /** Invalidated when battle state changes (e.g. opponent switches). */
    public void invalidateTrainerPlannedCache() {
        trainerPlannedCacheKey = "";
        cachedPlannedAction = null;
    }

    private TrainerPlannedAction computeTrainerPlannedAction(float activeHp) {
        boolean forceSwitch = activeHp <= 0;
        if (forceSwitch) {
            List<String> oppTypes = getOpponentTypes();
            var party = CobblemonClient.INSTANCE.getStorage().getParty();
            if (party == null) return TrainerPlannedAction.switchTo("forced switch", "?", -1);
            String bestName = null;
            double bestScore = -1;
            try {
                @SuppressWarnings("unchecked")
                java.util.List<Pokemon> slots = (java.util.List<Pokemon>) party.getClass().getMethod("getSlots").invoke(party);
                for (Pokemon p : slots) {
                    if (p == null || p.getCurrentHealth() <= 0) continue;
                    float hpPct = getPokemonHpPercent(p);
                    double offensive = evaluateOffensivePotential(p, oppTypes);
                    double score = offensive * (hpPct / 100.0);
                    String name = getSpeciesDisplayName(p.getSpecies());
                    if (score > bestScore) {
                        bestScore = score;
                        bestName = name;
                    }
                }
            } catch (Exception ignored) {}
            return TrainerPlannedAction.switchTo(
                    "forced switch (fainted)",
                    bestName != null ? bestName : "?",
                    bestScore >= 0 ? bestScore : -1);
        }
        // FIGHT: best move from active's move set (same scoring as chooseBestMove)
        Pokemon active = getActivePokemonFromParty(getActivePokemonNameInternal());
        List<String> oppTypes = getOpponentTypes();
        List<String> myTypes = getActiveTypes();
        if (active == null) return TrainerPlannedAction.fight("no party data", "?", "?", -1);
        String bestMoveName = null;
        String bestMoveType = null;
        double bestScore = -1;
        try {
            Object moveSetObj = active.getClass().getMethod("getMoveSet").invoke(active);
            java.util.List<?> moves = (java.util.List<?>) moveSetObj.getClass().getMethod("getMoves").invoke(moveSetObj);
            for (Object moveObj : moves) {
                if (moveObj == null) continue;
                try {
                    MoveTemplate tpl = (MoveTemplate) moveObj.getClass().getMethod("getTemplate").invoke(moveObj);
                    String moveName = getMoveTemplateName(tpl);
                    if (moveName == null || moveName.isEmpty()) continue;
                    MoveInfo info = lookupMove(moveName);
                    double eff = TypeChart.getEffectiveness(info.type, oppTypes);
                    double stab = myTypes.contains(info.type) ? 1.5 : 1.0;
                    double score = info.power > 0 ? info.power * eff * stab : 5.0;
                    if (score > bestScore) {
                        bestScore = score;
                        bestMoveName = moveName;
                        bestMoveType = info.type;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        String reason = "no voluntary switch";
        if (bestMoveName == null) bestMoveName = "?";
        if (bestMoveType == null) bestMoveType = "?";
        return TrainerPlannedAction.fight(reason, bestMoveName, bestMoveType, bestScore >= 0 ? bestScore : -1);
    }

    private static String getMoveTemplateName(MoveTemplate tpl) {
        if (tpl == null) return null;
        try {
            Object name = tpl.getClass().getMethod("getName").invoke(tpl);
            return name != null ? name.toString() : null;
        } catch (Exception e) {
            return null;
        }
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
            /** When advising a switch: best move that Pokémon would use vs current opponent. */
            String bestMoveName,
            /** When advising a switch: expected damage range (% of target HP) for that move. */
            String bestDamageRangePercent
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
        invalidateTrainerPlannedCache();
    }

    private AdvisorInfo computeAdvisor(String oppDisplay, String activeDisplay, String activeNameInternal) {
        List<String> oppTypes = getOpponentTypes();
        if (oppTypes.isEmpty()) return null;

        var party = CobblemonClient.INSTANCE.getStorage().getParty();
        if (party == null) return null;

        String bestNameDisplay = null;
        com.cobblemon.mod.common.pokemon.Species bestSpecies = null;
        Pokemon bestPokemon = null;
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
            double offensive = evaluateOffensivePotential(p, oppTypes);
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
                bestPokemon = p;
                bestTypes = pTypes;
                bestEff = TypeChart.getBestStabEffectiveness(pTypes, oppTypes);
            }
        }

        if (bestNameDisplay == null || bestSpecies == null) return null;

        // When active is KOd it was skipped in the loop so activeScore == -1: always advise best non-KOd
        boolean activeIsFainted = (activeScore < 0);
        boolean isSamePokemon = bestSpecies.getName().equalsIgnoreCase(activeNameInternal);
        boolean hasBetter = activeIsFainted
                || (!isSamePokemon && (activeScore < 0 || bestScore > activeScore * 1.2));

        // Official damage range (% of opponent's HP) for current active's best move
        String damageRangePercent = null;
        Pokemon activePokemon = getActivePokemonFromParty(activeNameInternal);
        ClientBattlePokemon opponentBp = getOpponentBattlePokemon();
        if (activePokemon != null && opponentBp != null) {
            DamageCalculator.DamageRange range = DamageCalculator.computeBestMoveDamage(
                    activePokemon, opponentBp, oppTypes, getActiveTypes());
            if (range != null && range.isValid()) {
                damageRangePercent = range.formatPercentRange();
            }
        }

        // When advising a switch: best move and expected damage for that Pokémon vs current opponent
        String bestMoveName = null;
        String bestDamageRangePercent = null;
        if (hasBetter && bestPokemon != null && opponentBp != null) {
            DamageCalculator.DamageRange bestRange = DamageCalculator.computeBestMoveDamage(
                    bestPokemon, opponentBp, oppTypes, bestTypes);
            if (bestRange != null && bestRange.isValid()) {
                bestMoveName = formatMoveName(bestRange.moveName());
                bestDamageRangePercent = bestRange.formatPercentRange();
            }
        }

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
                bestDamageRangePercent
        );
    }

    private static String formatMoveName(String moveName) {
        if (moveName == null || moveName.isEmpty()) return "?";
        String normalized = moveName.replaceAll("^[a-z]+:", "").trim();
        if (normalized.isEmpty()) return moveName;
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1).toLowerCase().replaceAll("_", " ");
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
