package com.cobblemoon.autoqiqi.npc;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles tower NPC interactions with walk-to support:
 * <ul>
 *   <li><b>Directeur de la tour</b> (entrance): interact to open floor menu, then click "Accès à l'étage n 1" to teleport to floor 1.</li>
 *   <li>Floor combat NPC (e.g. Dresseur, Directeur de combat): interact to engage the floor combat.</li>
 * </ul>
 * Scans in a wide radius ({@value SEARCH_RANGE} blocks) and walks toward NPCs that are beyond
 * Minecraft's interaction range ({@value INTERACT_RANGE} blocks).
 */
public class TowerNpcEngine {
    private static final TowerNpcEngine INSTANCE = new TowerNpcEngine();

    /** Entrance NPC - matches "Directeur de la tour". "Retour au spawn" also contains "tour", so we match on "directeur". */
    private static final String TOWER_ENTRANCE_MATCH = "directeur";

    /**
     * EasyNPC entity class name used on this server.
     * Floor combat trainers are Humanoid entities with personal names (Léo, Ethan, Kai, etc.),
     * so we detect them by entity type rather than name keywords.
     */
    private static final String EASYNPC_CLASS_NAME = "Humanoid";

    /** NPCs to skip during floor combat search (case-insensitive substring match).
     *  Ground-floor NPCs (Strat, Joyaux, etc.) are never reached because
     *  "Directeur de la tour" is found first on the ground floor. */
    private static final String[] NPC_EXCLUSIONS = {
            "retour au spawn",
            "directeur de la tour"
    };

    /** Wide scan radius to find NPCs after teleport */
    private static final double SEARCH_RANGE = 30.0;
    /** Minecraft entity interaction range */
    private static final double INTERACT_RANGE = 5.0;
    /** Stop walking when this close (slightly inside interact range for reliability) */
    private static final double ARRIVAL_DISTANCE = 3.5;

    /** Delay before auto-engaging floor combat after teleport (ms) */
    private static final long FLOOR_COMBAT_DELAY_MS = 5000;
    /** Delay before restarting tower after defeat (ms) */
    private static final long TOWER_RESTART_DELAY_MS = 4000;
    /** Max time spent walking to an NPC before giving up (ms) */
    private static final long WALK_TIMEOUT_MS = 15_000;

    /** Block scan radius for healing machine */
    private static final int HEALER_SCAN_RADIUS = 15;
    private static final int HEALER_SCAN_Y_RANGE = 5;
    /** Block interaction range */
    private static final double HEALER_INTERACT_RANGE = 3.5;
    /** Time to wait after right-clicking the healing machine (ms) */
    private static final long HEALING_WAIT_MS = 6000;
    private static final Identifier HEALING_MACHINE_ID = Identifier.of("cobblemon", "healing_machine");

    private enum State { IDLE, WALKING_TO_ENTRANCE, WALKING_TO_FLOOR_NPC, WALKING_TO_HEALER }

    private State state = State.IDLE;
    private Entity walkTarget = null;
    private BlockPos healerTarget = null;
    private long walkStartMs = 0;

    /** Entity IDs of NPCs that didn't produce a combat dialog — skip on retry. Cleared on floor change. */
    private final Set<Integer> npcBlacklist = new HashSet<>();
    /** Entity ID of the last NPC we interacted with (for blacklisting on dialog mismatch) */
    private int lastInteractedNpcId = -1;

    /** Tick when we last interacted with an NPC (for "no dialog opened" timeout) */
    private long interactionTick = -1;
    /** Ticks to wait for a dialog to open before blacklisting the NPC */
    private static final int NO_DIALOG_TIMEOUT_TICKS = 40;

    /** True when we engaged a floor combat NPC */
    private boolean towerFloorBattleActive = false;
    /** True when we've confirmed a Cobblemon battle started during tower floor combat */
    private boolean towerBattleConfirmed = false;
    /** Debounce: ticks since getBattle() returned null after a confirmed tower battle */
    private int battleEndNullTicks = 0;
    private static final int BATTLE_END_DEBOUNCE = 10;

    /** When true, the tower loop is active and will auto-restart after battles. */
    private boolean towerLoopEnabled = false;

    private TowerNpcEngine() {}

    public static TowerNpcEngine get() { return INSTANCE; }
    public boolean isActive() { return state != State.IDLE; }
    public boolean isLoopEnabled() { return towerLoopEnabled; }

    /** Stops the tower loop — no restart after the current battle ends. */
    public void stopLoop() {
        towerLoopEnabled = false;
        towerFloorBattleActive = false;
        towerBattleConfirmed = false;
        battleEndNullTicks = 0;
        cancelWalk("tower loop stopped");
        AutoQiqiClient.log("Tower", "Tower loop stopped by user");
    }

    /**
     * Attempts to find and interact with a tower NPC.
     * Checks for "Directeur de la tour" first (ground floor → enter tower),
     * then falls back to floor combat NPC search (tower floor → engage trainer).
     *
     * @return true if interaction was initiated or walk started
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

        towerLoopEnabled = true;

        // Entrance first: if "Directeur de la tour" is nearby, we're on the ground floor.
        // Only search for floor combat NPCs if no entrance NPC exists (= we're on a tower floor).
        Entity npc = findEntranceNpc(client, player);
        boolean isEntrance = (npc != null);
        if (npc == null) {
            npc = findAnyFloorCombatNpc(client, player);
        }

        if (npc == null) {
            AutoQiqiClient.log("Tower", "tryStartTower: no tower NPC found within " + SEARCH_RANGE + " blocks");
            return false;
        }

        double dist = player.distanceTo(npc);
        if (dist > INTERACT_RANGE) {
            startWalkingTo(npc, isEntrance ? State.WALKING_TO_ENTRANCE : State.WALKING_TO_FLOOR_NPC);
            AutoQiqiClient.log("Tower", "tryStartTower: NPC '" + getEntityName(npc) + "' found at " + String.format("%.1f", dist)
                    + "m — walking closer (need <" + INTERACT_RANGE + "m)");
            return true;
        }

        return interactWith(client, player, npc, isEntrance);
    }

    /**
     * Called every client tick. Handles walking toward NPCs and battle-end detection.
     */
    public void tick() {
        // Battle-end detection: when a tower floor battle ends, restart the tower
        if (towerFloorBattleActive) {
            boolean inBattle = CobblemonClient.INSTANCE.getBattle() != null;
            if (inBattle) {
                towerBattleConfirmed = true;
                battleEndNullTicks = 0;
            } else if (towerBattleConfirmed) {
                battleEndNullTicks++;
                if (battleEndNullTicks >= BATTLE_END_DEBOUNCE) {
                    towerFloorBattleActive = false;
                    towerBattleConfirmed = false;
                    battleEndNullTicks = 0;
                    if (towerLoopEnabled) {
                        AutoQiqiClient.log("Tower", "Tower battle ended — scheduling heal then restart");
                        AutoQiqiClient.runLater(this::tryHealBeforeRestart, TOWER_RESTART_DELAY_MS);
                    } else {
                        AutoQiqiClient.log("Tower", "Tower battle ended — loop disabled, stopping");
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.player != null) {
                            mc.player.sendMessage(Text.literal("§e[Tower]§r Tour arrêtée."), false);
                        }
                    }
                }
            }
        }

        // No-dialog timeout: if we interacted but no screen opened, blacklist and retry
        if (interactionTick >= 0 && TowerGuiHandler.get().isExpectingDialog()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            long elapsed = mc.world != null ? mc.world.getTime() - interactionTick : 0;
            if (mc.currentScreen == null && elapsed >= NO_DIALOG_TIMEOUT_TICKS) {
                AutoQiqiClient.log("Tower", "No dialog opened after " + NO_DIALOG_TIMEOUT_TICKS
                        + " ticks — blacklisting entity #" + lastInteractedNpcId);
                TowerGuiHandler.get().setExpectingTowerDialog(false);
                interactionTick = -1;
                if (lastInteractedNpcId != -1) {
                    npcBlacklist.add(lastInteractedNpcId);
                    lastInteractedNpcId = -1;
                }
                AutoQiqiClient.runLater(this::tryEngageFloorCombat, 500);
            }
        }

        if (state == State.IDLE) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) { cancelWalk("no player/world"); return; }
        if (client.currentScreen != null) { MovementHelper.releaseMovementKeys(client); return; }

        if (System.currentTimeMillis() - walkStartMs > WALK_TIMEOUT_MS) {
            boolean wasHealer = (state == State.WALKING_TO_HEALER);
            cancelWalk("timeout (" + (WALK_TIMEOUT_MS / 1000) + "s)");
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§c[Tower]§r Timeout — " +
                        (wasHealer ? "impossible d'atteindre la machine de soin." : "impossible d'atteindre le NPC.")), false);
            }
            if (wasHealer) {
                AutoQiqiClient.log("Tower", "Healer walk timeout — skipping heal, restarting tower");
                tryRestartTower();
            }
            return;
        }

        // Walking to healing machine block
        if (state == State.WALKING_TO_HEALER) {
            if (healerTarget == null) { cancelWalk("no healer target"); return; }

            double dist = Math.sqrt(player.getBlockPos().getSquaredDistance(healerTarget));
            if (dist <= HEALER_INTERACT_RANGE) {
                MovementHelper.releaseMovementKeys(client);
                AutoQiqiClient.log("Tower", "Arrived at healing machine (" + String.format("%.1f", dist) + "m)");
                state = State.IDLE;
                BlockPos pos = healerTarget;
                healerTarget = null;
                interactWithHealer(client, player, pos);
                return;
            }

            MovementHelper.lookAtPoint(player, Vec3d.ofCenter(healerTarget), 15f, 10f);
            client.options.forwardKey.setPressed(true);
            client.options.sprintKey.setPressed(dist > 6.0);
            if (player.horizontalCollision) {
                client.options.jumpKey.setPressed(true);
            }
            return;
        }

        // Walking to NPC (entrance or floor combat)
        if (walkTarget == null || !walkTarget.isAlive() || walkTarget.isRemoved()) {
            cancelWalk("target disappeared");
            return;
        }

        double dist = player.distanceTo(walkTarget);
        if (dist <= ARRIVAL_DISTANCE) {
            MovementHelper.releaseMovementKeys(client);
            AutoQiqiClient.log("Tower", "Arrived at NPC '" + getEntityName(walkTarget) + "' (" + String.format("%.1f", dist) + "m)");
            boolean isEntrance = (state == State.WALKING_TO_ENTRANCE);
            Entity npc = walkTarget;
            state = State.IDLE;
            walkTarget = null;
            interactWith(client, player, npc, isEntrance);
            return;
        }

        MovementHelper.lookAtEntity(player, walkTarget, 15f, 10f);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(dist > 6.0);
        if (player.horizontalCollision) {
            client.options.jumpKey.setPressed(true);
        }
    }

    /**
     * Schedules auto-engage with floor combat NPC after teleport.
     * Called when we click "Accès à l'étage n 1" in the tower dialog.
     */
    public void scheduleFloorCombatEngage() {
        npcBlacklist.clear();
        AutoQiqiClient.log("Tower", "Scheduling floor combat engage in " + (FLOOR_COMBAT_DELAY_MS / 1000) + "s (blacklist cleared)");
        AutoQiqiClient.runLater(this::tryEngageFloorCombat, FLOOR_COMBAT_DELAY_MS);
    }

    /**
     * Finds and interacts with the floor combat NPC on the current floor.
     * If too far, starts walking toward it.
     *
     * @return true if interaction was initiated or walk started
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
            AutoQiqiClient.log("Tower", "tryEngageFloorCombat: no floor combat NPC found within " + SEARCH_RANGE + " blocks");
            return false;
        }

        double dist = player.distanceTo(npc);
        if (dist > INTERACT_RANGE) {
            startWalkingTo(npc, State.WALKING_TO_FLOOR_NPC);
            AutoQiqiClient.log("Tower", "tryEngageFloorCombat: NPC '" + getEntityName(npc) + "' at " + String.format("%.1f", dist)
                    + "m — walking closer");
            return true;
        }

        return interactWith(client, player, npc, false);
    }

    /** Called by TowerGuiHandler when a dialog actually opens (cancels no-dialog timeout). */
    public void clearInteractionTick() {
        interactionTick = -1;
    }

    /**
     * Called by TowerGuiHandler when a dialog didn't contain expected combat/floor buttons.
     * Blacklists the NPC and retries with the next one.
     */
    public void onDialogMismatch() {
        if (lastInteractedNpcId != -1) {
            npcBlacklist.add(lastInteractedNpcId);
            AutoQiqiClient.log("Tower", "Dialog mismatch — blacklisted entity #" + lastInteractedNpcId
                    + " (total blacklisted: " + npcBlacklist.size() + ")");
            lastInteractedNpcId = -1;
        }
        towerFloorBattleActive = false;
        AutoQiqiClient.runLater(this::tryEngageFloorCombat, 1000);
    }

    /** Called when defeat is detected in chat. Schedules tower restart after teleport. */
    public void onDefeatDetected() {
        if (!towerFloorBattleActive) return;
        towerFloorBattleActive = false;
        AutoQiqiClient.log("Tower", "Defeat detected - scheduling tower restart in " + (TOWER_RESTART_DELAY_MS / 1000) + "s");
        AutoQiqiClient.runLater(this::tryRestartTower, TOWER_RESTART_DELAY_MS);
    }

    // ========================
    // Internal
    // ========================

    private boolean interactWith(MinecraftClient client, ClientPlayerEntity player, Entity npc, boolean isEntrance) {
        if (!MovementHelper.hasLineOfSight(player, npc)) {
            AutoQiqiClient.log("Tower", "interactWith: no line of sight to '" + getEntityName(npc) + "'");
            return false;
        }

        MovementHelper.lookAtEntity(player, npc, 20f, 15f);
        var result = client.interactionManager.interactEntity(player, npc, Hand.MAIN_HAND);
        double dist = player.distanceTo(npc);
        AutoQiqiClient.log("Tower", "interactWith: result=" + result
                + " npc='" + getEntityName(npc) + "' type=" + (isEntrance ? "entrance" : "combat")
                + " dist=" + String.format("%.1f", dist));

        lastInteractedNpcId = npc.getId();
        interactionTick = client.world != null ? client.world.getTime() : -1;
        TowerGuiHandler.get().setExpectingTowerDialog(true);
        if (!isEntrance) {
            towerFloorBattleActive = true;
            towerBattleConfirmed = false;
            battleEndNullTicks = 0;
        }

        return result.isAccepted() || result.shouldSwingHand();
    }

    private void startWalkingTo(Entity npc, State targetState) {
        state = targetState;
        walkTarget = npc;
        walkStartMs = System.currentTimeMillis();
        AutoQiqiClient.log("Tower", "Walking to '" + getEntityName(npc) + "' (state=" + targetState + ")");
    }

    private void cancelWalk(String reason) {
        if (state != State.IDLE) {
            AutoQiqiClient.log("Tower", "Walk cancelled: " + reason);
        }
        state = State.IDLE;
        walkTarget = null;
        healerTarget = null;
        MinecraftClient client = MinecraftClient.getInstance();
        MovementHelper.releaseMovementKeys(client);
    }

    // ========================
    // Healing machine
    // ========================

    private void tryHealBeforeRestart() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            AutoQiqiClient.log("Tower", "tryHealBeforeRestart: no player/world — skipping heal, restarting");
            tryRestartTower();
            return;
        }

        BlockPos healer = scanForHealingMachine(client, player);
        if (healer == null) {
            AutoQiqiClient.log("Tower", "No healing machine found within " + HEALER_SCAN_RADIUS + " blocks — skipping heal");
            tryRestartTower();
            return;
        }

        double dist = Math.sqrt(player.getBlockPos().getSquaredDistance(healer));
        if (dist <= HEALER_INTERACT_RANGE) {
            interactWithHealer(client, player, healer);
        } else {
            healerTarget = healer;
            state = State.WALKING_TO_HEALER;
            walkStartMs = System.currentTimeMillis();
            AutoQiqiClient.log("Tower", "Walking to healing machine at " + healer.toShortString()
                    + " (dist=" + String.format("%.1f", dist) + ")");
        }
    }

    private BlockPos scanForHealingMachine(MinecraftClient client, ClientPlayerEntity player) {
        BlockPos center = player.getBlockPos();
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (int dx = -HEALER_SCAN_RADIUS; dx <= HEALER_SCAN_RADIUS; dx++) {
            for (int dz = -HEALER_SCAN_RADIUS; dz <= HEALER_SCAN_RADIUS; dz++) {
                if (dx * dx + dz * dz > HEALER_SCAN_RADIUS * HEALER_SCAN_RADIUS) continue;
                for (int dy = -HEALER_SCAN_Y_RANGE; dy <= HEALER_SCAN_Y_RANGE; dy++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState blockState = client.world.getBlockState(pos);
                    Identifier blockId = Registries.BLOCK.getId(blockState.getBlock());
                    if (HEALING_MACHINE_ID.equals(blockId)) {
                        double distSq = center.getSquaredDistance(pos);
                        if (distSq < nearestDistSq) {
                            nearest = pos;
                            nearestDistSq = distSq;
                        }
                    }
                }
            }
        }

        if (nearest != null) {
            AutoQiqiClient.log("Tower", "Found healing machine at " + nearest.toShortString()
                    + " (dist=" + String.format("%.1f", Math.sqrt(nearestDistSq)) + ")");
        }
        return nearest;
    }

    private void interactWithHealer(MinecraftClient client, ClientPlayerEntity player, BlockPos pos) {
        MovementHelper.lookAtPoint(player, Vec3d.ofCenter(pos), 20f, 15f);
        BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        var result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
        AutoQiqiClient.log("Tower", "Interacted with healing machine at " + pos.toShortString() + " result=" + result);
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§a[Tower]§r Soin en cours..."), false);
        }
        AutoQiqiClient.runLater(this::tryRestartTower, HEALING_WAIT_MS);
    }

    private void tryRestartTower() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (tryStartTower()) {
            AutoQiqiClient.log("Tower", "Tower restart initiated");
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§a[Tower]§r Redémarrage de la tour..."), false);
            }
        } else {
            AutoQiqiClient.log("Tower", "Tower restart failed - no NPC found");
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§c[Tower]§r Directeur de la tour introuvable."), false);
            }
        }
    }

    // ========================
    // NPC search
    // ========================

    /**
     * Find any EasyNPC Humanoid entity that is NOT in the exclusion list.
     * Tower trainers have personal names (Léo, Ethan, Kai...) so we match by entity type.
     * Returns the nearest matching NPC.
     */
    private Entity findAnyFloorCombatNpc(MinecraftClient client, ClientPlayerEntity player) {
        Box searchBox = player.getBoundingBox().expand(SEARCH_RANGE);
        List<Entity> entities = client.world.getOtherEntities(player, searchBox, e -> !e.isRemoved());

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        StringBuilder allHumanoids = new StringBuilder();
        int humanoidCount = 0;

        for (Entity e : entities) {
            String className = e.getClass().getSimpleName();
            if (!className.equals(EASYNPC_CLASS_NAME)) continue;

            String name = getEntityName(e);
            double dist = player.distanceTo(e);
            boolean excluded = isExcludedNpc(name);
            boolean blacklisted = npcBlacklist.contains(e.getId());

            allHumanoids.append("\n  [").append(humanoidCount++).append("] '")
                    .append(name != null ? name : "null").append("' dist=")
                    .append(String.format("%.1f", dist))
                    .append(excluded ? " EXCLUDED" : "")
                    .append(blacklisted ? " BLACKLISTED" : "");

            if (excluded || blacklisted) continue;

            if (dist < nearestDist) {
                nearest = e;
                nearestDist = dist;
            }
        }

        AutoQiqiClient.log("Tower", "Floor scan — " + humanoidCount + " Humanoids found:" + allHumanoids);

        if (nearest != null) {
            AutoQiqiClient.log("Tower", "Selected floor combat NPC: '" + getEntityName(nearest)
                    + "' (dist=" + String.format("%.1f", nearestDist) + ")");
        }
        return nearest;
    }

    private boolean isExcludedNpc(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase();
        for (String excl : NPC_EXCLUSIONS) {
            if (lower.contains(excl.toLowerCase())) return true;
        }
        return false;
    }

    private Entity findEntranceNpc(MinecraftClient client, ClientPlayerEntity player) {
        Box searchBox = player.getBoundingBox().expand(SEARCH_RANGE);
        List<Entity> entities = client.world.getOtherEntities(player, searchBox, e -> !e.isRemoved());

        String match = TOWER_ENTRANCE_MATCH.toLowerCase();

        for (Entity e : entities) {
            String name = getEntityName(e);
            if (name == null) continue;
            if (name.toLowerCase().contains(match)) {
                AutoQiqiClient.log("Tower", "Found entrance NPC: '" + name + "' dist=" + String.format("%.1f",
                        client.player != null ? client.player.distanceTo(e) : -1));
                return e;
            }
        }

        logNearbyEntities("entrance (directeur)", entities);
        return null;
    }

    /** Log nearby entity names when no NPC matched — for debugging. */
    private void logNearbyEntities(String context, List<Entity> entities) {
        AutoQiqiClient.log("Tower", "No NPC found for " + context + ". Entities in range (" + SEARCH_RANGE + "b): " + entities.size());
        int maxLog = 15;
        for (int i = 0; i < Math.min(entities.size(), maxLog); i++) {
            Entity e = entities.get(i);
            String name = getEntityName(e);
            String type = e.getClass().getSimpleName();
            double dist = MinecraftClient.getInstance().player != null ? MinecraftClient.getInstance().player.distanceTo(e) : -1;
            AutoQiqiClient.log("Tower", "  [" + i + "] " + type + " name=\"" + (name != null ? name : "null") + "\" dist=" + String.format("%.1f", dist));
        }
        if (entities.size() > maxLog) {
            AutoQiqiClient.log("Tower", "  ... and " + (entities.size() - maxLog) + " more");
        }
    }

    private String getEntityName(Entity entity) {
        if (entity == null) return null;
        try {
            Text display = entity.getDisplayName();
            if (display != null) {
                String s = display.getString();
                if (s != null && !s.isBlank()) return s;
            }
            if (entity.hasCustomName()) {
                Text custom = entity.getCustomName();
                return custom != null ? custom.getString() : null;
            }
            return null;
        } catch (Exception ex) {
            AutoQiqiClient.log("Tower", "getEntityName failed for " + entity.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }
}
