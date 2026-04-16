package com.cobblemoon.autoqiqi.legendary.predict;

import java.util.Map;
import java.util.Set;

/**
 * Spawn requirements for a single legendary pokemon.
 * Loaded from spawns.json (reverse-engineered from LegendTracker).
 */
public record SpawnCondition(
        String name,
        Set<String> times,
        Set<String> weathers,
        Set<String> biomeTags,
        int maxY,
        int minY,
        boolean underground,
        boolean underwater,
        int weight
) {

    /**
     * Check if a world tick (0-23999) falls within any of the given time labels.
     * Uses the same hour-based logic as LegendTracker's LegendaryData.a(long):
     *   int hour = (int)((tick / 1000 + 6) % 24);
     */
    public static boolean isTimeMatch(long tick, Set<String> times) {
        if (times == null || times.isEmpty()) return true;
        tick = ((tick % 24000) + 24000) % 24000;
        int hour = (int) ((tick / 1000 + 6) % 24);
        for (String label : times) {
            if (isHourMatch(hour, label.toUpperCase())) return true;
        }
        return false;
    }

    private static boolean isHourMatch(int hour, String label) {
        return switch (label) {
            case "DAY", "JOURNÉE" -> hour >= 6 && hour < 18;
            case "DAWN"           -> hour == 5;
            case "MORNING"        -> hour >= 6 && hour < 11;
            case "MIDDAY"         -> hour >= 11 && hour < 13;
            case "AFTERNOON"      -> hour >= 13 && hour < 18;
            case "DUSK"           -> hour == 18;
            case "NIGHT"          -> hour >= 19 || hour < 5;
            case "MIDNIGHT"       -> hour >= 23 || hour <= 1;
            default               -> false;
        };
    }
}
