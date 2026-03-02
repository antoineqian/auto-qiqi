package com.cobblemoon.autoqiqi.battle;

/**
 * Domain action for the next step in a capture battle.
 * Used by {@link CaptureStrategy} and executed by {@link CaptureEngine}.
 * No Minecraft/Cobblemon dependency.
 */
public enum CaptureAction {
    FALSE_SWIPE,
    THUNDER_WAVE,
    SWITCH_FOR_FALSE_SWIPE,
    SWITCH_FOR_THUNDER_WAVE,
    SWITCH_TANK,
    THROW_BALL
}
