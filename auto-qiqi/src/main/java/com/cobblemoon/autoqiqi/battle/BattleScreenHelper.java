package com.cobblemoon.autoqiqi.battle;

import net.minecraft.client.MinecraftClient;

import com.cobblemon.mod.common.client.CobblemonClient;

/**
 * Detects whether the client is in a battle screen. Prefer Cobblemon battle state over
 * screen class name so detection works with obfuscated or mod-replaced UIs (e.g. Extended Battle UI).
 */
public final class BattleScreenHelper {

    private BattleScreenHelper() {}

    /**
     * True when the player is in an active battle (Cobblemon has a battle), so we consider
     * the client "in battle screen" for attribution and unfocused autofight. Does not depend
     * on the current screen class name.
     */
    public static boolean isInBattleScreen(MinecraftClient client) {
        if (client == null) return false;
        if (CobblemonClient.INSTANCE.getBattle() != null) return true;
        // Fallback: screen class name (e.g. if battle object is cleared before screen closes)
        return client.currentScreen != null
                && client.currentScreen.getClass().getSimpleName().toLowerCase().contains("battle");
    }
}
