package com.cobblemoon.autoqiqi.battle;

/**
 * Immutable snapshot of current battle state for capture strategy.
 * Built by {@link CaptureEngine} from Cobblemon battle; no Minecraft/Cobblemon types here.
 */
public record BattleSnapshot(
    String activePokemonName,
    float activeHpPercent,
    float opponentHpPercent,
    String opponentPokemonName,
    String opponentStatus
) {
    /** Unknown/missing value for HP. */
    public static final float HP_UNKNOWN = -1f;
}
