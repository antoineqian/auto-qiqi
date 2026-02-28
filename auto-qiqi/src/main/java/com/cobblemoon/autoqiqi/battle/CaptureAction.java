package com.cobblemoon.autoqiqi.battle;

/**
 * Domain action for the next step in a capture battle.
 * Used by {@link CaptureStrategy} and executed by {@link CaptureEngine}.
 * No Minecraft/Cobblemon dependency.
 */
public enum CaptureAction {
    FALSE_SWIPE,
    THUNDER_WAVE,
    SWITCH_MAROWAK,
    SWITCH_DRAGONITE,
    SWITCH_TANK,
    THROW_BALL
}
