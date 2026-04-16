package com.cobblemoon.autoqiqi.legendary.predict;

/**
 * A home location parsed from legendtracker.properties, with all the
 * environment data needed for local spawn prediction.
 */
public record HomeDefinition(
        String key,
        String world,
        String biomeId,
        String command,
        int yLevel,
        boolean isUnderground,
        boolean isUnderwater
) {}
