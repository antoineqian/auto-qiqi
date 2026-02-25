package com.cobblemoon.autoqiqi.battle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full Gen 6+ (18-type) effectiveness chart.
 * Only non-1.0 entries are stored; lookups default to neutral (1.0).
 */
public final class TypeChart {

    private static final Map<String, Map<String, Double>> CHART = new HashMap<>();

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
        if (attackType == null || defenderTypes == null || defenderTypes.isEmpty()) return 1.0;
        Map<String, Double> row = CHART.get(attackType.toLowerCase());
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
        double best = 0;
        for (String atkType : attackerTypes) {
            double eff = getEffectiveness(atkType, defenderTypes);
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
