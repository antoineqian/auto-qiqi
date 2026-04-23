package com.cobblemoon.autoqiqi.legendary;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.biome.BiomeDiscoveryEngine;
import com.cobblemoon.autoqiqi.npc.TowerNpcEngine;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import com.cobblemoon.autoqiqi.common.TimerParser;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.autohop.AutoHopEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

import com.cobblemoon.autoqiqi.common.ChatUtil;
import com.cobblemoon.autoqiqi.common.MovementHelper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles incoming chat messages for legendary timer parsing,
 * spawn detection, and current world detection.
 */
public class ChatMessageHandler {
    private static final ChatMessageHandler INSTANCE = new ChatMessageHandler();

    private long clearanceCooldownUntilMs = 0;
    private static final long POST_CLEAR_COOLDOWN_MS = 15_000;

    private Pattern timerPattern;
    private Pattern timerPatternSecondsOnly;
    private Pattern eventNowPattern;
    private Pattern legendarySpawnPattern;

    private String pendingPollWorld = null;
    private long pendingPollTimestamp = 0;
    private static final long POLL_TIMEOUT_MS = 25000; // 25s — server can be slow (dimension load, randomized time)

    /** When true, next timer parse updates the global (single) timer instead of a world. */
    private boolean pendingPollGlobal = false;

    private boolean suppressNextChatMessages = false;
    private long suppressUntil = 0;

    private String lastLegendaryHandled = null;
    private long lastLegendaryHandledAt = 0;
    private double[] pendingLegendaryCoords = null;
    private static final long LEGENDARY_DEDUP_MS = 5000;

    private ChatMessageHandler() {
        recompilePatterns();
    }

    public static ChatMessageHandler get() { return INSTANCE; }

    public boolean isEntityClearancePending() {
        return clearanceCooldownUntilMs > 0 && System.currentTimeMillis() < clearanceCooldownUntilMs;
    }

    public void recompilePatterns() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        try {
            timerPattern = Pattern.compile(config.timerPattern);
            timerPatternSecondsOnly = Pattern.compile(config.timerPatternSecondsOnly);
            eventNowPattern = Pattern.compile(config.eventNowPattern);
            legendarySpawnPattern = Pattern.compile(config.legendarySpawnPattern);
        } catch (Exception e) {
            AutoQiqiClient.logDebug("Chat", "Failed to compile regex patterns: " + e.getMessage());
        }
    }

    public void setPendingPoll(String worldName) {
        AutoQiqiClient.logDebug("Chat", "setPendingPoll: waiting for timer response for '" + worldName + "'");
        this.pendingPollWorld = worldName;
        this.pendingPollGlobal = false;
        this.pendingPollTimestamp = System.currentTimeMillis();
    }

    /** Wait for the next timer message and apply it to the global (single) timer. */
    public void setPendingPollGlobal() {
        AutoQiqiClient.logDebug("Chat", "setPendingPollGlobal: waiting for global timer response");
        this.pendingPollWorld = null;
        this.pendingPollGlobal = true;
        this.pendingPollTimestamp = System.currentTimeMillis();
    }

    /** Cancel any pending global poll (used when auto-hop takes over to prevent interference). */
    public void clearPendingPollGlobal() {
        if (this.pendingPollGlobal) {
            AutoQiqiClient.logDebug("Chat", "clearPendingPollGlobal: cancelled stale global poll");
        }
        this.pendingPollGlobal = false;
    }

    public void suppressMessages(long durationMs) {
        this.suppressNextChatMessages = true;
        this.suppressUntil = System.currentTimeMillis() + durationMs;
    }

    public boolean isSuppressing() {
        if (suppressNextChatMessages && System.currentTimeMillis() > suppressUntil) {
            suppressNextChatMessages = false;
        }
        return suppressNextChatMessages;
    }

    public boolean onChatMessage(String message, Text textObject) {
        String stripped = TimerParser.stripFormatting(message);
        if (stripped.contains("[Auto-Qiqi]") || stripped.contains("[AutoLeg]")) return false;


        // Entity clearance: "Les entités seront supprimées dans 1 minutes."
        if (stripped.contains("entit") && stripped.contains("supprim")) {
            if (stripped.contains("dans")) {
                AutoQiqiClient.logDebug("Chat", "Entity clearance warning detected — still engaging until clear happens");
            } else {
                clearanceCooldownUntilMs = System.currentTimeMillis() + POST_CLEAR_COOLDOWN_MS;
                AutoQiqiClient.logDebug("Chat", "Entity clearance done, pausing target acquisition for " + (POST_CLEAR_COOLDOWN_MS / 1000) + "s");
                ChatUtil.msg("§6[Auto-Qiqi]§r §eEntities cleared — pausing scans for " + (POST_CLEAR_COOLDOWN_MS / 1000) + "s");
            }
        }

        // Biome discovery: flight toggle detection
        if (stripped.toLowerCase().contains("mode vol")) {
            BiomeDiscoveryEngine.get().onFlightChatMessage(stripped);
        }

        // Tower defeat: restart tower when we lose a floor battle
        if (isDefeatMessage(stripped)) {
            TowerNpcEngine.get().onDefeatDetected();
        }

        // Capture detection: "Votre équipe est pleine. X a été ajouté a votre PC."
        if (stripped.contains("a été ajouté") && stripped.contains("votre PC")) {
            handleCaptureSuccess(stripped);
        }

        // Teleport confirmation: walk in random direction for 0.5s then advance auto-hop
        if (stripped.contains("Téléportation terminée") || stripped.contains("Teleportation terminee")) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                Random rng = new Random();
                // Pick a random yaw and start walking forward
                float randomYaw = mc.player.getYaw() + rng.nextFloat() * 360f - 180f;
                mc.player.setYaw(randomYaw);
                mc.options.forwardKey.setPressed(true);
                AutoQiqiClient.logDebug("Chat", "Teleport confirmed — random walk for 0.5s");
                // After 0.5s, stop walking and advance state
                AutoQiqiClient.runLater(() -> {
                    MovementHelper.releaseMovementKeys(mc);
                    ChatUtil.sendCommand(mc.player, "nextleg");
                    AutoHopEngine.get().onTeleportConfirmed();
                }, 500);
            } else {
                AutoHopEngine.get().onTeleportConfirmed();
            }
        }

        // TPA request detection (on alt account): auto-accept incoming TPA only from trusted players.
        // Match known French phrasings for /tpahere requests.
        if (stripped.contains("a demandé que vous vous téléportiez")
                || stripped.contains("a demande que vous vous teleportiez")
                || stripped.contains("demande de téléportation")
                || stripped.contains("demande de teleportation")) {
            // Extract sender: look for a player name at the start, or after "de "
            String sender = null;
            // Try: "PlayerName a demandé que vous ..."
            Matcher tpaMatcher = Pattern.compile("^(.+?)\\s+a demande?é? que vous").matcher(stripped);
            if (tpaMatcher.find()) {
                sender = tpaMatcher.group(1).trim();
            }
            // Try: "Demande de téléportation de PlayerName"
            if (sender == null) {
                Matcher fromMatcher = Pattern.compile("(?:demande|requête).*?de\\s+([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE).matcher(stripped);
                if (fromMatcher.find()) {
                    sender = fromMatcher.group(1).trim();
                }
            }
            // Fallback: check if any trusted name appears in the message
            if (sender == null) {
                String lower = stripped.toLowerCase();
                if (lower.contains("qiqiqlann")) sender = "Qiqiqlann";
                else if (lower.contains("ketamaxxing")) sender = "KetaMaxxing";
            }
            boolean trusted = sender != null
                    && (sender.toLowerCase().contains("qiqiqlann") || sender.toLowerCase().contains("ketamaxxing"));
            if (trusted) {
                ChatUtil.msg("§6[Auto-Hop]§r TPA reçue de §e" + sender + "§r — /tpaccept (délai)");
                // Small delay (1-2s) before accepting to look more natural
                MinecraftClient mc = MinecraftClient.getInstance();
                AutoQiqiClient.runLater(() -> ChatUtil.sendCommand(mc.player, "tpaccept"),
                        1000 + new Random().nextInt(1000));
            }
        }

        // TPA completion detection (on main account): alt has teleported to us
        if (stripped.contains("s'est téléporté") || stripped.contains("s'est teleporte")
                || stripped.contains("a accepté votre demande") || stripped.contains("a accepte votre demande")) {
            AutoHopEngine.get().onTpaCompleted();
        }

        if (legendarySpawnPattern != null) {
            Matcher spawnMatcher = legendarySpawnPattern.matcher(stripped);
            if (spawnMatcher.find()) {
                handleLegendarySpawn(spawnMatcher.group(1).trim(), spawnMatcher.group(2).trim(), stripped);
            }
        }

        if (eventNowPattern != null) {
            Matcher eventMatcher = eventNowPattern.matcher(stripped);
            if (eventMatcher.find()) {
                handleEventNow();
                return false;
            }
        }

        Pattern mondeHere = Pattern.compile("(?i)monde\\s+(\\S+?)\\s*<--\\s*\\[ICI\\]");
        Matcher mondeMatcher = mondeHere.matcher(stripped);
        if (mondeMatcher.find()) {
            String currentWorld = mondeMatcher.group(1).toLowerCase();
            WorldTracker.get().setCurrentWorld(currentWorld);
            AutoQiqiClient.logDebug("Legendary", "Detected current world: " + currentWorld);
        }

        if (pendingPollGlobal
                && (System.currentTimeMillis() - pendingPollTimestamp) < POLL_TIMEOUT_MS) {
            Long seconds = tryParseTimer(stripped);
            if (seconds != null) {
                WorldTracker.get().updateGlobalTimer(seconds);
                long waitMs = System.currentTimeMillis() - pendingPollTimestamp;
                AutoQiqiClient.logDebug("Chat", "Global timer parsed: " + seconds + "s (response took " + waitMs + "ms)");
                pendingPollGlobal = false;
                return isSuppressing();
            }
        }

        if (pendingPollWorld != null
                && (System.currentTimeMillis() - pendingPollTimestamp) < POLL_TIMEOUT_MS) {
            Long seconds = tryParseTimer(stripped);
            if (seconds != null) {
                String cleanWorld = pendingPollWorld.replaceAll("<--.*", "").trim();
                WorldTracker.get().updateWorldTimer(cleanWorld, seconds);
                long waitMs = System.currentTimeMillis() - pendingPollTimestamp;
                AutoQiqiClient.logDebug("Chat", "Timer parsed for " + cleanWorld + ": " + seconds + "s (response took " + waitMs + "ms)");
                pendingPollWorld = null;
                return isSuppressing();
            }
        }

        // Parse "Apparitions possibles: Giratina: 75.00%, Genesect: 25.00%"
        if (stripped.contains("Apparitions possibles")) {
            Map<String, Double> spawns = parseSpawnProbabilities(stripped);
            if (!spawns.isEmpty()) {
                AutoQiqiClient.logDebug("Chat", "Spawn probabilities parsed: " + spawns);
                AutoHopEngine.get().onSpawnProbabilitiesParsed(spawns);
            }
        }

        return false;
    }

    private static final Pattern SPAWN_PROB_PATTERN = Pattern.compile(
            "([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ:0-9 -]+?):\\s*(\\d+[.,]\\d+)%");

    private Map<String, Double> parseSpawnProbabilities(String message) {
        // Extract everything after "Apparitions possibles:"
        int idx = message.indexOf("Apparitions possibles");
        if (idx < 0) return Map.of();
        String tail = message.substring(idx);

        Map<String, Double> result = new LinkedHashMap<>();
        Matcher m = SPAWN_PROB_PATTERN.matcher(tail);
        while (m.find()) {
            String name = m.group(1).trim();
            // Skip the "Apparitions possibles" key itself
            if (name.toLowerCase().contains("apparitions")) continue;
            try {
                double prob = Double.parseDouble(m.group(2).replace(',', '.'));
                result.put(name, prob);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static boolean isDefeatMessage(String stripped) {
        String lower = stripped.toLowerCase();
        return lower.contains("défaite") || lower.contains("defaite")
                || lower.contains("vous avez perdu") || lower.contains("perdu!")
                || (lower.contains("perdu") && (lower.contains("combat") || lower.contains("battle")))
                || lower.contains("defeat");
    }

    private Long tryParseTimer(String message) {
        return TimerParser.parse(message, timerPattern, timerPatternSecondsOnly);
    }

    private static final Pattern COORD_PATTERN = Pattern.compile(
            "coordonn[eé]es\\s*\\((-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)\\)");

    private void handleLegendarySpawn(String pokemonName, String nearPlayer, String fullMessage) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        String ourName = player.getName().getString();
        boolean isSelfReference = nearPlayer.equalsIgnoreCase("vous") || nearPlayer.equalsIgnoreCase("toi");
        boolean isNearUs = isSelfReference || nearPlayer.equalsIgnoreCase(ourName);
        AutoQiqiConfig config = AutoQiqiConfig.get();

        // Check legendary ignore list — skip entirely as if nothing spawned
        boolean ignored = config.legendaryIgnoreList.stream()
                .anyMatch(s -> s.equalsIgnoreCase(pokemonName));
        if (ignored) {
            AutoQiqiClient.logDebug("Legendary", "Ignoring " + pokemonName + " (in legendaryIgnoreList)");
            return;
        }

        // Check if the legendary spawned near the alt account
        String altName = config.autohopTpaAltAccount;
        boolean isNearAlt = altName != null && !altName.isEmpty()
                && nearPlayer.equalsIgnoreCase(altName) && !isNearUs;

        double[] coords = parseCoordinates(fullMessage);

        if (isSelfReference && coords != null) {
            pendingLegendaryCoords = coords;
            AutoQiqiClient.logDebug("Legendary", "Stored coords from 'pres de vous' message: ("
                    + (int)coords[0] + "," + (int)coords[1] + "," + (int)coords[2] + ")");
        }

        long now = System.currentTimeMillis();
        boolean isDuplicate = pokemonName.equalsIgnoreCase(lastLegendaryHandled)
                && (now - lastLegendaryHandledAt) < LEGENDARY_DEDUP_MS;

        if (isNearUs || isNearAlt) {
            if (isDuplicate) {
                AutoQiqiClient.logDebug("Legendary", "Dedup: skipping second spawn message for " + pokemonName);
                return;
            }
            lastLegendaryHandled = pokemonName;
            lastLegendaryHandledAt = now;
        }

        String currentWorld = WorldTracker.get().getCurrentWorld();
        if ((isNearUs || isNearAlt) && !isDuplicate) {
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logLegendarySpawn(
                    pokemonName, currentWorld != null ? currentWorld : "unknown", isNearUs);
        }

        // Pause and auto-engage when legendary is near us — including "pres de vous" (isSelfReference) and "pres de [playerName]".
        if (isNearUs || isNearAlt) {
            // Play alert sound for legendaries near us or our alt
            client.execute(() -> {
                if (client.player != null) {
                    client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
                    client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
                    client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 2.0f);
                }
            });
        }

        if (isNearUs) {
            if (!AutoHopEngine.get().isDisabled()) {
                AutoQiqiClient.logDebug("Legendary", "Disabling auto-hop — legendary spawned near us: " + pokemonName);
                AutoHopEngine.get().setDisabled(true);
                ChatUtil.msg("§c[Auto-Hop]§r Désactivé (légendaire apparu). Réactivez manuellement.");
            }

            // Stop biome discovery immediately so it doesn't teleport us away mid-engage
            if (BiomeDiscoveryEngine.get().isEnabled()) {
                AutoQiqiClient.logDebug("Legendary", "Stopping biome discovery — legendary spawned near us");
                BiomeDiscoveryEngine.get().stop();
            }

            double[] effectiveCoords = coords != null ? coords : pendingLegendaryCoords;
            pendingLegendaryCoords = null;

            client.execute(() -> {
                if (client.player == null || client.world == null) return;
                Entity legendaryEntity = findLegendaryEntity(client, pokemonName, effectiveCoords);
                if (legendaryEntity != null) {
                    String name = PokemonScanner.getPokemonName(legendaryEntity);
                    int level = PokemonScanner.getPokemonLevel(legendaryEntity);

                    if (level > 0 && level != 70) {
                        AutoQiqiClient.logDebug("Legendary", "Skipping " + name + " Lv." + level + " — expected Lv.70 for wild legendary");
                        client.player.sendMessage(
                                Text.literal("§d§l[Auto-Qiqi] §e" + name + " Lv." + level + " §c— pas niveau 70, ignore."), false);
                        return;
                    }

                    AutoQiqiClient.logDebug("Legendary", "Auto-engage: " + name + " Lv." + level
                            + " dist=" + String.format("%.1f", client.player.distanceTo(legendaryEntity))
                            + " -> ENGAGE (manual battle)");

                    client.player.sendMessage(
                            Text.literal("§d§l[Auto-Qiqi] §a§l" + pokemonName
                                    + " §e§ldetecte ! §7Engagement auto — combat manuel."),
                            false);
                    AutoBattleEngine.get().forceTarget(legendaryEntity);
                } else {
                    AutoQiqiClient.logDebug("Legendary", "Auto-engage: could not find entity for " + pokemonName
                            + (effectiveCoords != null ? " near coords (" + (int)effectiveCoords[0] + "," + (int)effectiveCoords[1] + "," + (int)effectiveCoords[2] + ")" : " (no coords)"));
                    // Entity not found — schedule re-enable so we don't stay stuck
                    AutoHopEngine.get().scheduleReEnable(120);
                    client.player.sendMessage(
                            Text.literal("§d§l[Auto-Qiqi] §a§l" + pokemonName
                                    + " §e§lest apparu pres de vous ! §c§lEntite introuvable, capture manuelle requise. §7Réactivation auto-hop dans 120s."),
                            false);
                }
            });
        } else if (isNearAlt) {
            // Legendary spawned on alt account — treat same as near us (both at same location via /tpahere)
            if (!AutoHopEngine.get().isDisabled()) {
                AutoQiqiClient.logDebug("Legendary", "Disabling auto-hop — legendary spawned near alt (" + altName + "): " + pokemonName);
                AutoHopEngine.get().setDisabled(true);
                ChatUtil.msg("§c[Auto-Hop]§r Désactivé (légendaire apparu sur §e" + altName + "§r).");
            }

            // Stop biome discovery immediately so it doesn't teleport us away mid-engage
            if (BiomeDiscoveryEngine.get().isEnabled()) {
                AutoQiqiClient.logDebug("Legendary", "Stopping biome discovery — legendary spawned near alt");
                BiomeDiscoveryEngine.get().stop();
            }

            double[] effectiveCoords = coords != null ? coords : pendingLegendaryCoords;
            pendingLegendaryCoords = null;

            client.execute(() -> {
                if (client.player == null || client.world == null) return;
                Entity legendaryEntity = findLegendaryEntity(client, pokemonName, effectiveCoords);
                if (legendaryEntity != null) {
                    String name = PokemonScanner.getPokemonName(legendaryEntity);
                    int level = PokemonScanner.getPokemonLevel(legendaryEntity);

                    if (level > 0 && level != 70) {
                        AutoQiqiClient.logDebug("Legendary", "Skipping " + name + " Lv." + level + " — expected Lv.70 for wild legendary (alt spawn)");
                        client.player.sendMessage(
                                Text.literal("§d§l[Auto-Qiqi] §e" + name + " Lv." + level + " §c— pas niveau 70, ignore."), false);
                        AutoHopEngine.get().scheduleReEnable(15);
                        return;
                    }

                    AutoQiqiClient.logDebug("Legendary", "Auto-engage (alt spawn): " + name + " Lv." + level
                            + " dist=" + String.format("%.1f", client.player.distanceTo(legendaryEntity))
                            + " -> ENGAGE (manual battle)");

                    client.player.sendMessage(
                            Text.literal("§d§l[Auto-Qiqi] §a§l" + pokemonName
                                    + " §e§ldetecte (alt §b" + altName + "§e§l) ! §7Engagement auto — combat manuel."),
                            false);
                    AutoBattleEngine.get().forceTarget(legendaryEntity);
                } else {
                    AutoQiqiClient.logDebug("Legendary", "Auto-engage (alt spawn): could not find entity for " + pokemonName
                            + (effectiveCoords != null ? " near coords (" + (int)effectiveCoords[0] + "," + (int)effectiveCoords[1] + "," + (int)effectiveCoords[2] + ")" : " (no coords)"));
                    AutoHopEngine.get().scheduleReEnable(90);
                    client.player.sendMessage(
                            Text.literal("§d§l[Auto-Qiqi] §a§l" + pokemonName
                                    + " §e§lest apparu sur l'alt §b" + altName + "§e§l ! §c§lEntite introuvable. §7Réactivation auto-hop dans 90s."),
                            false);
                }
            });
        } else {
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("§d[Auto-Qiqi] §7" + pokemonName
                                    + " est apparu pres de " + nearPlayer),
                            false);
                }
            });
        }
    }

    private double[] parseCoordinates(String message) {
        Matcher m = COORD_PATTERN.matcher(message);
        if (m.find()) {
            try {
                return new double[] {
                        Double.parseDouble(m.group(1)),
                        Double.parseDouble(m.group(2)),
                        Double.parseDouble(m.group(3))
                };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Entity findLegendaryEntity(MinecraftClient client, String pokemonName, double[] coords) {
        if (client.player == null || client.world == null) return null;

        String cleanName = pokemonName.replaceAll("^[^a-zA-ZÀ-ÿ]+", "").trim().toLowerCase();

        double scanRange = 120.0;
        List<Entity> candidates = client.world.getOtherEntities(
                client.player,
                client.player.getBoundingBox().expand(scanRange),
                entity -> entity instanceof PokemonEntity && entity.isAlive()
        );

        Entity bestMatch = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : candidates) {
            String entityName = PokemonScanner.getPokemonName(entity).toLowerCase();
            if (!entityName.contains(cleanName) && !cleanName.contains(entityName)) continue;

            double dist;
            if (coords != null) {
                double dx = entity.getX() - coords[0];
                double dy = entity.getY() - coords[1];
                double dz = entity.getZ() - coords[2];
                dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            } else {
                dist = client.player.distanceTo(entity);
            }

            if (dist < bestDist) {
                bestDist = dist;
                bestMatch = entity;
            }
        }

        if (bestMatch != null) {
            AutoQiqiClient.logDebug("Legendary", "Found legendary entity: " + PokemonScanner.getPokemonName(bestMatch)
                    + " dist=" + String.format("%.1f", bestDist)
                    + " dex=" + PokemonScanner.getPokedexStatus(bestMatch)
                    + " pos=(" + (int)bestMatch.getX() + "," + (int)bestMatch.getY() + "," + (int)bestMatch.getZ() + ")");
        } else {
            AutoQiqiClient.logDebug("Legendary", "No matching entity found among " + candidates.size()
                    + " pokemon in range (looking for '" + cleanName + "')");
        }

        return bestMatch;
    }

    private void handleCaptureSuccess(String stripped) {
        // Extract Pokemon name from "X a été ajouté a votre PC."
        Matcher m = Pattern.compile("(.+?)\\s+a été ajouté").matcher(stripped);
        String pokemonName = m.find() ? m.group(1).trim() : "Unknown";
        // Remove leading "Votre équipe est pleine." prefix if present
        pokemonName = pokemonName.replaceAll("(?i).*pleine\\.?\\s*", "").trim();
        if (pokemonName.isEmpty()) pokemonName = "Unknown";

        AutoQiqiClient.logDebug("Chat", "Capture confirmed via chat: " + pokemonName);
        AutoBattleEngine.get().recordCapture(pokemonName);
        CaptureEngine.get().onCaptureConfirmedByChat(pokemonName);
    }

    private void handleEventNow() {
        WorldTracker tracker = WorldTracker.get();
        String currentWorld = tracker.getCurrentWorld();
        if (currentWorld != null) {
            tracker.setEventActive(currentWorld, true);
            AutoQiqiClient.logDebug("Chat", "EVENT NOW detected in: " + currentWorld);
        } else {
            AutoQiqiClient.logDebug("Chat", "EVENT NOW detected but currentWorld is null!");
        }
    }

}
