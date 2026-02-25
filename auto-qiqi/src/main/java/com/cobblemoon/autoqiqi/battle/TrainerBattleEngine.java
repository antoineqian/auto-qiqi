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
     * Score = basePower × typeEffectiveness × STAB.
     * Status moves (power 0) get a small flat score so they are only chosen
     * if every damaging move is immune / heavily resisted.
     */
    public MoveTile chooseBestMove(List<MoveTile> tiles) {
        List<String> oppTypes = getOpponentTypes();
        List<String> myTypes = getActiveTypes();

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
        try {
            MoveTemplate tpl = Moves.INSTANCE.getByName(moveName);
            if (tpl != null) {
                String type = tpl.getElementalType().getName().toLowerCase();
                double power = tpl.getPower();
                return new MoveInfo(type, power);
            }
        } catch (Exception e) {
            AutoQiqiClient.log("Trainer", "Move lookup failed for '" + moveName + "': " + e.getMessage());
        }
        return new MoveInfo("normal", 50);
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

    private String getActivePokemonName() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return "?";
            var bp = battle.getSide1().getActors().get(0).getActivePokemon().get(0).getBattlePokemon();
            return bp != null ? bp.getSpecies().getName() : "?";
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

    private String getOpponentPokemonName() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return "?";
            var bp = battle.getSide2().getActors().get(0).getActivePokemon().get(0).getBattlePokemon();
            return bp != null ? bp.getSpecies().getName() : "?";
        } catch (Exception e) { return "?"; }
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
            boolean hasBetterOption
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

            String oppName = getOpponentPokemonName();
            String myName = getActivePokemonName();
            String cacheKey = oppName + "|" + myName;

            if (cacheKey.equals(advisorCacheKey) && cachedAdvisor != null) {
                advisorCacheTimeMs = now;
                return cachedAdvisor;
            }

            cachedAdvisor = computeAdvisor(oppName, myName);
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

    private AdvisorInfo computeAdvisor(String oppName, String activeName) {
        List<String> oppTypes = getOpponentTypes();
        if (oppTypes.isEmpty()) return null;

        var party = CobblemonClient.INSTANCE.getStorage().getParty();
        if (party == null) return null;

        String bestName = null;
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

            String name = p.getSpecies().getName();
            if (name.equalsIgnoreCase(activeName)) {
                activeScore = score;
            }

            if (score > bestScore) {
                bestScore = score;
                bestName = name;
                bestTypes = pTypes;
                bestEff = TypeChart.getBestStabEffectiveness(pTypes, oppTypes);
            }
        }

        if (bestName == null) return null;

        boolean isSamePokemon = bestName.equalsIgnoreCase(activeName);
        boolean hasBetter = !isSamePokemon
                && (activeScore < 0 || bestScore > activeScore * 1.2);

        return new AdvisorInfo(
                oppName,
                formatTypes(oppTypes),
                activeName,
                activeScore,
                bestName,
                formatTypes(bestTypes),
                bestScore,
                bestEff,
                hasBetter
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
