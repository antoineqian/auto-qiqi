package com.cobblemoon.autoqiqi.npc;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * Handles tower NPC interactions:
 * <ul>
 *   <li><b>Directeur de la tour</b> (entrance): interact to open floor menu, then click "Accès à l'étage n 1" to teleport to floor 1.</li>
 *   <li>Floor combat NPC (e.g. Dresseur, Directeur de combat): interact to engage the floor combat.</li>
 * </ul>
 * Excludes "Retour au spawn" and similar NPCs that would teleport out. Press key I near either NPC to interact.
 */
public class TowerNpcEngine {
    private static final TowerNpcEngine INSTANCE = new TowerNpcEngine();

    /** Entrance NPC - floor selection dialog. Must contain "tour" (tower), NOT "combat". */
    private static final String TOWER_ENTRANCE_MATCH = "tour";
    private static final String TOWER_ENTRANCE_EXCLUDE = "combat";

    /** Floor combat NPCs - try in order. Excludes "Directeur de la tour" (entrance). */
    private static final String[] FLOOR_COMBAT_NPC_NAMES = {
            "Dresseur",
            "Combat",
            "Adversaire"
    };

    /** NPCs to never interact with on floors */
    private static final String[] FLOOR_NPC_EXCLUSIONS = {
            "retour au spawn",
            "directeur de la tour"  // Entrance NPC - never use for floor combat
    };

    /** Max distance to interact with NPCs */
    private static final double INTERACT_RANGE = 5.0;

    /** Delay before auto-engaging floor combat after teleport (ms) */
    private static final long FLOOR_COMBAT_DELAY_MS = 5000;
    /** Delay before restarting tower after defeat (ms) - time to teleport back */
    private static final long TOWER_RESTART_DELAY_MS = 4000;

    /** True when we engaged a floor combat NPC - used to detect defeat and restart tower */
    private boolean towerFloorBattleActive = false;

    private TowerNpcEngine() {}

    public static TowerNpcEngine get() {
        return INSTANCE;
    }

    /**
     * Attempts to find and interact with a tower NPC.
     * Tries "Directeur de la tour" (entrance) first, then floor combat NPCs.
     * Call when key I is pressed.
     *
     * @return true if interaction was initiated, false if no NPC found or out of range
     */
    public boolean tryStartTower() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            AutoQiqiClient.log("Tower", "tryStartTower: no player/world/interactionManager");
            return false;
        }

        if (client.currentScreen != null) {
            AutoQiqiClient.log("Tower", "tryStartTower: screen already open, skipping");
            return false;
        }

        // 1. Try entrance NPC "Directeur de la tour" (must contain "tour", must NOT contain "combat")
        Entity npc = findEntranceNpc(client, player);
        boolean isEntrance = true;
        if (npc == null) {
            npc = findAnyFloorCombatNpc(client, player);
            isEntrance = false;
        }

        if (npc == null) {
            AutoQiqiClient.log("Tower", "tryStartTower: no tower NPC found");
            return false;
        }

        double dist = player.distanceTo(npc);
        if (dist > INTERACT_RANGE) {
            AutoQiqiClient.log("Tower", "tryStartTower: NPC too far (" + String.format("%.1f", dist) + " blocks)");
            return false;
        }

        if (!MovementHelper.hasLineOfSight(player, npc)) {
            AutoQiqiClient.log("Tower", "tryStartTower: no line of sight to NPC");
            return false;
        }

        // Face the NPC before interacting
        MovementHelper.lookAtEntity(player, npc, 20f, 15f);

        var result = client.interactionManager.interactEntity(player, npc, Hand.MAIN_HAND);
        AutoQiqiClient.log("Tower", "tryStartTower: interactEntity result=" + result
                + " npc=" + getEntityName(npc) + " type=" + (isEntrance ? "entrance" : "combat") + " dist=" + String.format("%.1f", dist));

        // Only expect floor-selection dialog for entrance NPC
        if (isEntrance) {
            TowerGuiHandler.get().setExpectingTowerDialog(true);
        } else {
            towerFloorBattleActive = true;
        }

        return result.isAccepted() || result.shouldSwingHand();
    }

    /**
     * Schedules auto-engage with floor combat NPC after teleport.
     * Called when we click "Accès à l'étage n 1" in the tower dialog.
     */
    public void scheduleFloorCombatEngage() {
        AutoQiqiClient.log("Tower", "Scheduling floor combat engage in " + (FLOOR_COMBAT_DELAY_MS / 1000) + "s");
        AutoQiqiClient.runLater(this::tryEngageFloorCombat, FLOOR_COMBAT_DELAY_MS);
    }

    /**
     * Finds and interacts with the floor combat NPC on the current floor.
     * Called automatically after teleport, or manually via key I.
     *
     * @return true if interaction was initiated
     */
    public boolean tryEngageFloorCombat() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            AutoQiqiClient.log("Tower", "tryEngageFloorCombat: no player/world/interactionManager");
            return false;
        }

        if (client.currentScreen != null) {
            AutoQiqiClient.log("Tower", "tryEngageFloorCombat: screen open, skipping");
            return false;
        }

        Entity npc = findAnyFloorCombatNpc(client, player);
        if (npc == null) {
            AutoQiqiClient.log("Tower", "tryEngageFloorCombat: no floor combat NPC found");
            return false;
        }

        double dist = player.distanceTo(npc);
        if (dist > INTERACT_RANGE) {
            AutoQiqiClient.log("Tower", "tryEngageFloorCombat: NPC too far (" + String.format("%.1f", dist) + " blocks)");
            return false;
        }

        if (!MovementHelper.hasLineOfSight(player, npc)) {
            AutoQiqiClient.log("Tower", "tryEngageFloorCombat: no line of sight");
            return false;
        }

        MovementHelper.lookAtEntity(player, npc, 20f, 15f);

        var result = client.interactionManager.interactEntity(player, npc, Hand.MAIN_HAND);
        AutoQiqiClient.log("Tower", "tryEngageFloorCombat: interactEntity result=" + result
                + " npc=" + getEntityName(npc) + " dist=" + String.format("%.1f", dist));

        if (result.isAccepted() || result.shouldSwingHand()) {
            towerFloorBattleActive = true;
        }
        return result.isAccepted() || result.shouldSwingHand();
    }

    /** Called when defeat is detected in chat. Schedules tower restart after teleport. */
    public void onDefeatDetected() {
        if (!towerFloorBattleActive) return;
        towerFloorBattleActive = false;
        AutoQiqiClient.log("Tower", "Defeat detected - scheduling tower restart in " + (TOWER_RESTART_DELAY_MS / 1000) + "s");
        AutoQiqiClient.runLater(this::tryRestartTower, TOWER_RESTART_DELAY_MS);
    }

    /** Restart tower: interact with Directeur de la tour, then floor 1 will be selected by TowerGuiHandler. */
    private void tryRestartTower() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (tryStartTower()) {
            AutoQiqiClient.log("Tower", "Tower restart initiated");
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§a[Tower]§r Redémarrage de la tour..."), false);
            }
        } else {
            AutoQiqiClient.log("Tower", "Tower restart failed - Directeur de la tour not found");
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§c[Tower]§r Directeur de la tour introuvable."), false);
            }
        }
    }

    private Entity findAnyFloorCombatNpc(MinecraftClient client, ClientPlayerEntity player) {
        Box searchBox = player.getBoundingBox().expand(INTERACT_RANGE);
        List<Entity> entities = client.world.getOtherEntities(player, searchBox, e -> !e.isRemoved());

        for (String nameFragment : FLOOR_COMBAT_NPC_NAMES) {
            String searchLower = nameFragment.toLowerCase();
            for (Entity e : entities) {
                String name = getEntityName(e);
                if (name != null && name.toLowerCase().contains(searchLower) && !isExcludedFloorNpc(name)) {
                    AutoQiqiClient.log("Tower", "Found floor combat NPC: '" + name + "' (matched '" + nameFragment + "')");
                    return e;
                }
            }
        }
        return null;
    }

    /** Returns true if this NPC should never be used for floor combat (e.g. "Retour au spawn"). */
    private boolean isExcludedFloorNpc(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase();
        for (String excl : FLOOR_NPC_EXCLUSIONS) {
            if (lower.contains(excl.toLowerCase())) {
                AutoQiqiClient.log("Tower", "Excluding NPC '" + name + "' (matches exclusion '" + excl + "')");
                return true;
            }
        }
        return false;
    }

    /** Find "Directeur de la tour" - name must contain "tour" and must NOT contain "combat". */
    private Entity findEntranceNpc(MinecraftClient client, ClientPlayerEntity player) {
        Box searchBox = player.getBoundingBox().expand(INTERACT_RANGE);
        List<Entity> entities = client.world.getOtherEntities(player, searchBox, e -> !e.isRemoved());

        String match = TOWER_ENTRANCE_MATCH.toLowerCase();
        String exclude = TOWER_ENTRANCE_EXCLUDE.toLowerCase();

        for (Entity e : entities) {
            String name = getEntityName(e);
            if (name == null) continue;
            String lower = name.toLowerCase();
            if (lower.contains(match) && !lower.contains(exclude)) {
                AutoQiqiClient.log("Tower", "Found entrance NPC: '" + name + "'");
                return e;
            }
        }
        return null;
    }

    private String getEntityName(Entity entity) {
        if (entity == null) return null;
        try {
            if (entity.hasCustomName()) {
                Text custom = entity.getCustomName();
                return custom != null ? custom.getString() : null;
            }
            Text display = entity.getDisplayName();
            return display != null ? display.getString() : null;
        } catch (Exception ex) {
            AutoQiqiClient.log("Tower", "getEntityName failed for " + entity.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }
}
