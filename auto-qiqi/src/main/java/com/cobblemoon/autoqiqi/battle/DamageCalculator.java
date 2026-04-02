package com.cobblemoon.autoqiqi.battle;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.List;

/**
 * Official Pokémon damage formula (Gen 5/6+).
 * Damage = ((((Level × 2/5 + 2) × Power × A/D / 50 + 2) × R/100) × STAB × TypeEff)
 * with R = 85..100 for damage range.
 */
public final class DamageCalculator {

    private static final double RANDOM_MIN = 0.85;
    private static final double RANDOM_MAX = 1.00;

    /**
     * Result of a damage calculation: range as raw damage and as % of defender's max HP.
     */
    public record DamageRange(int minDamage, int maxDamage, double minPercent, double maxPercent, String moveName) {
        public boolean isValid() {
            return minDamage >= 0 && maxDamage >= 0 && maxPercent >= 0;
        }

        public String formatPercentRange() {
            if (!isValid()) return null;
            return String.format("%.0f-%.0f%%", minPercent, maxPercent);
        }
    }

    /**
     * Compute damage range for the best damaging move from attacker to defender.
     * Uses attacker's and defender's level and stats (with reflection fallback).
     * Returns null if no damaging move or stats unavailable.
     * @param defenderSpeciesName optional; when set, species-based immunities (e.g. Heatran vs Fire) are applied.
     */
    public static DamageRange computeBestMoveDamage(
            Pokemon attacker,
            ClientBattlePokemon defender,
            List<String> defenderTypes,
            List<String> attackerTypes,
            String defenderSpeciesName
    ) {
        if (attacker == null || defender == null || defenderTypes == null) return null;
        int defenderHp = getMaxHp(defender);
        if (defenderHp <= 0) return null;

        int attLevel = getLevel(attacker);
        int attAtk = getStat(attacker, Stat.ATTACK);
        int attSpA = getStat(attacker, Stat.SPECIAL_ATTACK);
        int defDef = getStat(defender, Stat.DEFENSE);
        int defSpD = getStat(defender, Stat.SPECIAL_DEFENSE);

        BestMove best = findBestDamagingMove(attacker, defenderTypes, attackerTypes, defenderSpeciesName);
        if (best == null) return null;

        boolean physical = isPhysical(best.template);
        int attackStat = physical ? attAtk : attSpA;
        int defenseStat = physical ? defDef : defSpD;

        int level = attLevel;
        int power = Math.max(1, (int) best.template.getPower());
        double a = Math.max(1, attackStat);
        double d = Math.max(1, defenseStat);

        // Gen 5+ base: ((Level*2/5+2) * Power * A/D / 50 + 2), then floor
        double base = ((2.0 * level / 5.0 + 2.0) * power * (a / d) / 50.0 + 2.0);
        int baseInt = (int) Math.floor(base);

        double stab = attackerTypes != null && attackerTypes.contains(best.moveType.toLowerCase()) ? 1.5 : 1.0;
        double typeEff = TypeChart.getEffectiveness(best.moveType, defenderTypes, defenderSpeciesName);

        int dmgMin = applyModifiers(baseInt, RANDOM_MIN, stab, typeEff);
        int dmgMax = applyModifiers(baseInt, RANDOM_MAX, stab, typeEff);

        double minPct = 100.0 * dmgMin / defenderHp;
        double maxPct = 100.0 * dmgMax / defenderHp;

        return new DamageRange(dmgMin, dmgMax, minPct, maxPct, best.moveName);
    }

    /**
     * Compute damage range for a specific move from attacker to defender.
     * Returns null if the move is status (power 0) or stats unavailable.
     * @param defenderSpeciesName optional; when set, species-based immunities (e.g. Heatran vs Fire) are applied.
     */
    public static DamageRange computeMoveDamage(
            Pokemon attacker,
            ClientBattlePokemon defender,
            MoveTemplate moveTemplate,
            List<String> defenderTypes,
            List<String> attackerTypes,
            String defenderSpeciesName
    ) {
        if (attacker == null || defender == null || moveTemplate == null || defenderTypes == null) return null;
        double power = moveTemplate.getPower();
        if (power <= 0) return null;
        int defenderHp = getMaxHp(defender);
        if (defenderHp <= 0) return null;

        int attLevel = getLevel(attacker);
        int attAtk = getStat(attacker, Stat.ATTACK);
        int attSpA = getStat(attacker, Stat.SPECIAL_ATTACK);
        int defDef = getStat(defender, Stat.DEFENSE);
        int defSpD = getStat(defender, Stat.SPECIAL_DEFENSE);

        boolean physical = isPhysical(moveTemplate);
        int attackStat = physical ? attAtk : attSpA;
        int defenseStat = physical ? defDef : defSpD;

        String moveType = moveTemplate.getElementalType().getName().toLowerCase();
        double a = Math.max(1, attackStat);
        double d = Math.max(1, defenseStat);

        double base = ((2.0 * attLevel / 5.0 + 2.0) * power * (a / d) / 50.0 + 2.0);
        int baseInt = (int) Math.floor(base);

        double stab = attackerTypes != null && attackerTypes.contains(moveType) ? 1.5 : 1.0;
        double typeEff = TypeChart.getEffectiveness(moveType, defenderTypes, defenderSpeciesName);

        int dmgMin = applyModifiers(baseInt, RANDOM_MIN, stab, typeEff);
        int dmgMax = applyModifiers(baseInt, RANDOM_MAX, stab, typeEff);

        double minPct = 100.0 * dmgMin / defenderHp;
        double maxPct = 100.0 * dmgMax / defenderHp;

        String moveName = getMoveDisplayName(moveTemplate);
        return new DamageRange(dmgMin, dmgMax, minPct, maxPct, moveName);
    }

    private static int applyModifiers(int baseDamage, double random, double stab, double typeEff) {
        if (typeEff == 0) return 0; // immune
        double d = baseDamage * random * stab * typeEff;
        int result = (int) Math.floor(d);
        return Math.max(1, result);
    }

    private static class BestMove {
        final MoveTemplate template;
        final String moveName;
        final String moveType;

        BestMove(MoveTemplate template, String moveName, String moveType) {
            this.template = template;
            this.moveName = moveName;
            this.moveType = moveType;
        }
    }

    private static BestMove findBestDamagingMove(Pokemon attacker, List<String> defenderTypes, List<String> attackerTypes, String defenderSpeciesName) {
        BestMove best = null;
        double bestScore = 0;
        try {
            Object moveSetObj = attacker.getClass().getMethod("getMoveSet").invoke(attacker);
            java.util.List<?> moves = (java.util.List<?>) moveSetObj.getClass().getMethod("getMoves").invoke(moveSetObj);
            for (Object moveObj : moves) {
                if (moveObj == null) continue;
                try {
                    MoveTemplate tpl = (MoveTemplate) moveObj.getClass().getMethod("getTemplate").invoke(moveObj);
                    double pow = tpl.getPower();
                    if (pow <= 0) continue;
                    String mType = tpl.getElementalType().getName().toLowerCase();
                    double eff = TypeChart.getEffectiveness(mType, defenderTypes, defenderSpeciesName);
                    double stab = (attackerTypes != null && attackerTypes.contains(mType)) ? 1.5 : 1.0;
                    double score = pow * eff * stab;
                    if (score > bestScore) {
                        bestScore = score;
                        String name = getMoveDisplayName(tpl);
                        best = new BestMove(tpl, name, mType);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return best;
    }

    private static boolean isPhysical(MoveTemplate tpl) {
        try {
            Object cat = tpl.getClass().getMethod("getDamageCategory").invoke(tpl);
            if (cat == null) return true;
            String s = cat.toString().toLowerCase();
            return s.contains("physical");
        } catch (Exception e) {
            try {
                Object cat = tpl.getClass().getMethod("getCategory").invoke(tpl);
                if (cat == null) return true;
                String s = cat.toString().toLowerCase();
                return s.contains("physical");
            } catch (Exception e2) {
                return true;
            }
        }
    }

    enum Stat { ATTACK, DEFENSE, SPECIAL_ATTACK, SPECIAL_DEFENSE, SPEED, HP }

    private static int getLevel(Object pokemonOrBattlePokemon) {
        try {
            if (pokemonOrBattlePokemon instanceof Pokemon p) {
                return p.getLevel();
            }
            if (pokemonOrBattlePokemon instanceof ClientBattlePokemon bp) {
                try {
                    Object pokemon = bp.getClass().getMethod("getPokemon").invoke(bp);
                    if (pokemon instanceof Pokemon p) return p.getLevel();
                } catch (Exception ignored) {}
                try {
                    Object l = bp.getClass().getMethod("getLevel").invoke(bp);
                    if (l instanceof Number n) return n.intValue();
                } catch (Exception ignored) {}
                return 50;
            }
        } catch (Exception e) {
            return 50;
        }
        return 50;
    }

    private static int getMaxHp(ClientBattlePokemon bp) {
        try {
            double max = bp.getMaxHp();
            return max > 0 && max <= Integer.MAX_VALUE ? (int) max : 100;
        } catch (Exception e) {
            return 100;
        }
    }

    /**
     * Get stat value via reflection (Cobblemon Pokemon/ClientBattlePokemon).
     * Fallback: estimate from level (formula for non-HP: (2*base*L/100+5), base=100).
     */
    static int getStat(Object pokemonOrBattlePokemon, Stat stat) {
        int level = getLevel(pokemonOrBattlePokemon);
        try {
            if (pokemonOrBattlePokemon instanceof Pokemon p) {
                Integer v = getStatFromPokemon(p, stat);
                if (v != null) return Math.max(1, v);
            }
            if (pokemonOrBattlePokemon instanceof ClientBattlePokemon bp) {
                Integer v = getStatFromBattlePokemon(bp, stat);
                if (v != null) return Math.max(1, v);
            }
        } catch (Exception ignored) {}
        return estimatedStat(level, stat);
    }

    private static Integer getStatFromPokemon(Pokemon p, Stat stat) {
        try {
            Object stats = p.getClass().getMethod("getStats").invoke(p);
            if (stats != null) {
                try {
                    Object statEnum = statEnumFor(stat);
                    Object v = stats.getClass().getMethod("get", Object.class).invoke(stats, statEnum);
                    if (v instanceof Number n) return n.intValue();
                } catch (Exception e) {
                    String method = statMethodName(stat);
                    Object v = p.getClass().getMethod(method).invoke(p);
                    if (v instanceof Number n) return n.intValue();
                }
            }
        } catch (Exception e) {
            try {
                String method = statMethodName(stat);
                Object v = p.getClass().getMethod(method).invoke(p);
                if (v instanceof Number n) return n.intValue();
            } catch (Exception e2) {}
        }
        return null;
    }

    private static Integer getStatFromBattlePokemon(ClientBattlePokemon bp, Stat stat) {
        try {
            Object stats = bp.getClass().getMethod("getStats").invoke(bp);
            if (stats != null) {
                Object statEnum = statEnumFor(stat);
                Object v = stats.getClass().getMethod("get", Object.class).invoke(stats, statEnum);
                if (v instanceof Number n) return n.intValue();
            }
        } catch (Exception e) {
            try {
                String method = statMethodName(stat);
                Object v = bp.getClass().getMethod(method).invoke(bp);
                if (v instanceof Number n) return n.intValue();
            } catch (Exception e2) {}
        }
        try {
            Object pokemon = bp.getClass().getMethod("getPokemon").invoke(bp);
            if (pokemon instanceof Pokemon p) return getStatFromPokemon(p, stat);
        } catch (Exception ignored) {}
        return null;
    }

    private static Object statEnumFor(Stat stat) throws Exception {
        Class<?> statClass = Class.forName("com.cobblemon.mod.common.api.pokemon.stats.Stat");
        for (Object c : statClass.getEnumConstants()) {
            if (c.toString().equalsIgnoreCase(stat.name().replace("_", ""))
                    || c.toString().equalsIgnoreCase(stat.name())) {
                return c;
            }
        }
        return null;
    }

    private static String statMethodName(Stat stat) {
        return switch (stat) {
            case ATTACK -> "getAttack";
            case DEFENSE -> "getDefense";
            case SPECIAL_ATTACK -> "getSpecialAttack";
            case SPECIAL_DEFENSE -> "getSpecialDefense";
            case SPEED -> "getSpeed";
            case HP -> "getHp";
        };
    }

    private static int estimatedStat(int level, Stat stat) {
        if (stat == Stat.HP) {
            return (int) Math.max(1, (2 * 100 * level / 100.0 + level + 10));
        }
        return (int) Math.max(1, (2.0 * 100 * level / 100.0 + 5));
    }

    private static String getMoveDisplayName(MoveTemplate tpl) {
        try {
            try {
                Object name = tpl.getClass().getMethod("getName").invoke(tpl);
                if (name != null && !name.toString().isEmpty()) return name.toString();
            } catch (Exception ignored) {}
            try {
                Object id = tpl.getClass().getMethod("getId").invoke(tpl);
                if (id != null) return id.toString();
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return "?";
    }
}
