package com.cobblemoon.autoqiqi.legendary;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.npc.TowerNpcEngine;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import com.cobblemoon.autoqiqi.common.TimerParser;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

import java.util.List;
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

    private boolean suppressNextChatMessages = false;
    private long suppressUntil = 0;

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
            System.err.println("[Auto-Qiqi] Failed to compile regex patterns: " + e.getMessage());
        }
    }

    public void setPendingPoll(String worldName) {
        AutoQiqiClient.log("Chat", "setPendingPoll: waiting for timer response for '" + worldName + "'");
        this.pendingPollWorld = worldName;
        this.pendingPollTimestamp = System.currentTimeMillis();
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
                AutoQiqiClient.log("Chat", "Entity clearance warning detected — still engaging until clear happens");
            } else {
                clearanceCooldownUntilMs = System.currentTimeMillis() + POST_CLEAR_COOLDOWN_MS;
                AutoQiqiClient.log("Chat", "Entity clearance done, pausing target acquisition for " + (POST_CLEAR_COOLDOWN_MS / 1000) + "s");
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §eEntities cleared — pausing scans for " + (POST_CLEAR_COOLDOWN_MS / 1000) + "s"), false);
                }
            }
        }

        // Tower defeat: restart tower when we lose a floor battle
        if (isDefeatMessage(stripped)) {
            TowerNpcEngine.get().onDefeatDetected();
        }

        // Capture detection: "Votre équipe est pleine. X a été ajouté a votre PC."
        if (stripped.contains("a été ajouté") && stripped.contains("votre PC")) {
            handleCaptureSuccess(stripped);
        }

        if (!AutoQiqiConfig.get().legendaryEnabled) return false;

        if (legendarySpawnPattern != null && AutoQiqiConfig.get().legendarySpawnSoundEnabled) {
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
            AutoQiqiClient.log("Legendary", "Detected current world: " + currentWorld);
        }

        if (pendingPollWorld != null
                && (System.currentTimeMillis() - pendingPollTimestamp) < POLL_TIMEOUT_MS) {
            Long seconds = tryParseTimer(stripped);
            if (seconds != null) {
                String cleanWorld = pendingPollWorld.replaceAll("<--.*", "").trim();
                WorldTracker.get().updateWorldTimer(cleanWorld, seconds);
                long waitMs = System.currentTimeMillis() - pendingPollTimestamp;
                AutoQiqiClient.log("Chat", "Timer parsed for " + cleanWorld + ": " + seconds + "s (response took " + waitMs + "ms)");
                pendingPollWorld = null;
                AutoSwitchEngine.get().onTimerResponseReceived();
                return isSuppressing();
            }
        }

        return false;
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
        boolean isNearUs = nearPlayer.equalsIgnoreCase(ourName);
        AutoQiqiConfig config = AutoQiqiConfig.get();

        String currentWorld = WorldTracker.get().getCurrentWorld();
        if (isNearUs) {
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logLegendarySpawn(
                    pokemonName, currentWorld != null ? currentWorld : "unknown", true);
        }

        boolean shouldPlaySound = config.legendarySpawnSoundEnabled
                && (!config.legendarySpawnSoundOnlyForMe || isNearUs);

        if (shouldPlaySound) {
            int repeats = isNearUs
                    ? Math.max(1, Math.min(5, config.legendarySpawnSoundRepeats))
                    : 1;

            new Thread(() -> {
                for (int i = 0; i < repeats; i++) {
                    client.execute(() -> {
                        if (client.player != null && client.world != null) {
                            client.world.playSound(
                                    client.player.getX(), client.player.getY(), client.player.getZ(),
                                    SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                                    SoundCategory.MASTER,
                                    1.0f, 1.0f, false);
                        }
                    });
                    if (i < repeats - 1) {
                        try { Thread.sleep(600); } catch (InterruptedException e) { break; }
                    }
                }
            }, "AutoQiqi-Sound").start();
        }

        if (isNearUs) {
            AutoSwitchEngine.get().pauseForCapture(pokemonName);

            double[] coords = parseCoordinates(fullMessage);
            client.execute(() -> {
                if (client.player == null || client.world == null) return;
                Entity legendaryEntity = findLegendaryEntity(client, pokemonName, coords);
                if (legendaryEntity != null) {
                    String name = PokemonScanner.getPokemonName(legendaryEntity);
                    int level = PokemonScanner.getPokemonLevel(legendaryEntity);
                    AutoQiqiClient.log("Legendary", "Auto-capture: found " + name + " Lv." + level
                            + " at dist=" + String.format("%.1f", client.player.distanceTo(legendaryEntity)));
                    client.player.sendMessage(
                            Text.literal("§d§l[Auto-Qiqi] §a§l" + pokemonName
                                    + " §e§ldetecte ! §7Auto-capture en cours..."),
                            false);
                    CaptureEngine.get().start(name, level, true, legendaryEntity);
                } else {
                    AutoQiqiClient.log("Legendary", "Auto-capture: could not find entity for " + pokemonName
                            + (coords != null ? " near coords (" + (int)coords[0] + "," + (int)coords[1] + "," + (int)coords[2] + ")" : " (no coords)"));
                    client.player.sendMessage(
                            Text.literal("§d§l[Auto-Qiqi] §a§l" + pokemonName
                                    + " §e§lest apparu pres de vous ! §c§lEntite introuvable, capture manuelle requise. §7[J] pour reprendre"),
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
            if (!PokemonScanner.isLegendary(entity)) continue;
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
            AutoQiqiClient.log("Legendary", "Found legendary entity: " + PokemonScanner.getPokemonName(bestMatch)
                    + " dist=" + String.format("%.1f", bestDist)
                    + " pos=(" + (int)bestMatch.getX() + "," + (int)bestMatch.getY() + "," + (int)bestMatch.getZ() + ")");
        } else {
            AutoQiqiClient.log("Legendary", "No matching legendary entity found among " + candidates.size()
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

        AutoQiqiClient.log("Chat", "Capture confirmed via chat: " + pokemonName);
        AutoBattleEngine.get().recordCapture(pokemonName);
        CaptureEngine.get().onCaptureConfirmedByChat(pokemonName);
    }

    private void handleEventNow() {
        WorldTracker tracker = WorldTracker.get();
        String currentWorld = tracker.getCurrentWorld();
        if (currentWorld != null) {
            tracker.setEventActive(currentWorld, true);
            AutoQiqiClient.log("Chat", "EVENT NOW detected in: " + currentWorld);
        } else {
            AutoQiqiClient.log("Chat", "EVENT NOW detected but currentWorld is null!");
        }
    }

}
