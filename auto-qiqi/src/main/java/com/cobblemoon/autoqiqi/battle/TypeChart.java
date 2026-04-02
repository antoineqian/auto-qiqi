package com.cobblemoon.autoqiqi.battle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full Gen 6+ (18-type) effectiveness chart.
 * Only non-1.0 entries are stored; lookups default to neutral (1.0).
 * Species-based overrides (e.g. Heatran immune to Fire in Cobblemon) are applied when defender species is provided.
 */
public final class TypeChart {

    private static final Map<String, Map<String, Double>> CHART = new HashMap<>();

    /** Species (internal name, lowercase) that are immune to specific attack types (e.g. Heatran: Fire). */
    private static final Map<String, Set<String>> SPECIES_ATTACK_IMMUNITIES = new HashMap<>();
    static {
        SPECIES_ATTACK_IMMUNITIES.put("heatran", Set.of("fire"));
    }

    static {
        // Normal
        se("normal");
        nve("normal", "rock", "steel");
        imm("normal", "ghost");

        // Fire
        se("fire", "grass", "ice", "bug", "steel");
        nve("fire", "fire", "water", "rock", "dragon");

        // Water
        se("water", "fire", "ground", "rock");
        nve("water", "water", "grass", "dragon");

        // Electric
        se("electric", "water", "flying");
        nve("electric", "electric", "grass", "dragon");
        imm("electric", "ground");

        // Grass
        se("grass", "water", "ground", "rock");
        nve("grass", "fire", "grass", "poison", "flying", "bug", "dragon", "steel");

        // Ice
        se("ice", "grass", "ground", "flying", "dragon");
        nve("ice", "fire", "water", "ice", "steel");

        // Fighting
        se("fighting", "normal", "ice", "rock", "dark", "steel");
        nve("fighting", "poison", "flying", "psychic", "bug", "fairy");
        imm("fighting", "ghost");

        // Poison
        se("poison", "grass", "fairy");
        nve("poison", "poison", "ground", "rock", "ghost");
        imm("poison", "steel");

        // Ground
        se("ground", "fire", "electric", "poison", "rock", "steel");
        nve("ground", "grass", "bug");
        imm("ground", "flying");

        // Flying
        se("flying", "grass", "fighting", "bug");
        nve("flying", "electric", "rock", "steel");

        // Psychic
        se("psychic", "fighting", "poison");
        nve("psychic", "psychic", "steel");
        imm("psychic", "dark");

        // Bug
        se("bug", "grass", "psychic", "dark");
        nve("bug", "fire", "fighting", "poison", "flying", "ghost", "steel", "fairy");

        // Rock
        se("rock", "fire", "ice", "flying", "bug");
        nve("rock", "fighting", "ground", "steel");

        // Ghost
        se("ghost", "psychic", "ghost");
        nve("ghost", "dark");
        imm("ghost", "normal");

        // Dragon
        se("dragon", "dragon");
        nve("dragon", "steel");
        imm("dragon", "fairy");

        // Dark
        se("dark", "psychic", "ghost");
        nve("dark", "fighting", "dark", "fairy");

        // Steel
        se("steel", "ice", "rock", "fairy");
        nve("steel", "fire", "water", "electric", "steel");

        // Fairy
        se("fairy", "fighting", "dragon", "dark");
        nve("fairy", "fire", "poison", "steel");
    }

    private TypeChart() {}

    /**
     * Combined effectiveness of a single attack type vs all defender types.
     * E.g. Fire vs Grass/Steel = 2.0 * 2.0 = 4.0
     */
    public static double getEffectiveness(String attackType, List<String> defenderTypes) {
        return getEffectiveness(attackType, defenderTypes, null);
    }

    /**
     * Same as {@link #getEffectiveness(String, List)} but applies species-based overrides when defender species is known.
     * E.g. Heatran is immune to Fire in Cobblemon, so Fire vs Heatran returns 0.
     */
    public static double getEffectiveness(String attackType, List<String> defenderTypes, String defenderSpeciesName) {
        if (attackType == null || defenderTypes == null || defenderTypes.isEmpty()) return 1.0;
        String atk = attackType.toLowerCase();
        if (defenderSpeciesName != null && !defenderSpeciesName.isEmpty()) {
            Set<String> immunities = SPECIES_ATTACK_IMMUNITIES.get(defenderSpeciesName.toLowerCase());
            if (immunities != null && immunities.contains(atk)) return 0.0;
        }
        Map<String, Double> row = CHART.get(atk);
        double mult = 1.0;
        for (String def : defenderTypes) {
            if (row != null) {
                Double v = row.get(def.toLowerCase());
                if (v != null) mult *= v;
            }
        }
        return mult;
    }

    /**
     * Best offensive effectiveness any of the attacker's STAB types can achieve
     * against the defender's type combination.
     */
    public static double getBestStabEffectiveness(List<String> attackerTypes, List<String> defenderTypes) {
        return getBestStabEffectiveness(attackerTypes, defenderTypes, null);
    }

    /**
     * Same as {@link #getBestStabEffectiveness(List, List)} but applies species-based overrides when defender species is known.
     */
    public static double getBestStabEffectiveness(List<String> attackerTypes, List<String> defenderTypes, String defenderSpeciesName) {
        double best = 0;
        for (String atkType : attackerTypes) {
            double eff = getEffectiveness(atkType, defenderTypes, defenderSpeciesName);
            if (eff > best) best = eff;
        }
        return best;
    }

    // ---- helpers for building the chart ----

    private static void se(String atk, String... defs) {
        for (String d : defs) put(atk, d, 2.0);
    }

    private static void nve(String atk, String... defs) {
        for (String d : defs) put(atk, d, 0.5);
    }

    private static void imm(String atk, String... defs) {
        for (String d : defs) put(atk, d, 0.0);
    }

    private static void put(String atk, String def, double v) {
        CHART.computeIfAbsent(atk.toLowerCase(), k -> new HashMap<>())
                .put(def.toLowerCase(), v);
    }
}
