package com.cobblemoon.autoqiqi.npc;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.BattleScreenHelper;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Logs tower battle results to a dedicated file ({@code tower-battles.log}).
 * Each entry records: timestamp, trainer name, battle duration, result (WIN/LOSS),
 * and the player's team composition.
 */
public final class TowerLogger {
    private static final TowerLogger INSTANCE = new TowerLogger();

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LOG_FILE_NAME = "tower-battles.log";

    private long accumulatedMs = 0;
    private long segmentStartMs = 0;
    private boolean timerRunning = false;
    private String trainerName = null;
    private List<String> teamSnapshot = null;
    private final Set<String> opponentsSeen = new LinkedHashSet<>();

    private TowerLogger() {}

    public static TowerLogger get() { return INSTANCE; }

    /**
     * Called when a tower floor battle starts.
     * Records the start time, trainer NPC name, and current team.
     */
    public void onBattleStart(String npcName) {
        accumulatedMs = 0;
        segmentStartMs = System.currentTimeMillis();
        timerRunning = true;
        trainerName = npcName != null ? npcName : "Unknown";
        teamSnapshot = captureTeam();
        opponentsSeen.clear();
        AutoQiqiClient.logDebug("TowerLog", "Battle started vs " + trainerName + " | Team: " + teamSnapshot);
    }

    /**
     * Called every tick during a tower battle.
     * Records each unique opponent Pokémon and pauses/resumes the timer
     * when the battle screen is dismissed/restored (R key toggle).
     */
    public void trackOpponent() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean screenVisible = BattleScreenHelper.isInBattleScreen(mc);

        // Pause/resume timer based on battle screen visibility
        if (timerRunning && !screenVisible) {
            // Screen dismissed (R pressed) — pause
            accumulatedMs += System.currentTimeMillis() - segmentStartMs;
            timerRunning = false;
            AutoQiqiClient.logDebug("TowerLog", "Timer paused (screen dismissed)");
        } else if (!timerRunning && screenVisible) {
            // Screen restored (R pressed again) — resume
            segmentStartMs = System.currentTimeMillis();
            timerRunning = true;
            AutoQiqiClient.logDebug("TowerLog", "Timer resumed (screen restored)");
        }

        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return;
            ClientBattlePokemon bp = battle.getSide2().getActors().get(0)
                    .getActivePokemon().get(0).getBattlePokemon();
            if (bp == null) return;
            String species = bp.getSpecies().getName();
            if (species != null && !species.isEmpty()) {
                opponentsSeen.add(species);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Called when a tower floor battle ends (victory or defeat).
     */
    public void onBattleEnd(boolean victory) {
        if (accumulatedMs == 0 && !timerRunning) return;

        long durationMs = accumulatedMs;
        if (timerRunning) {
            durationMs += System.currentTimeMillis() - segmentStartMs;
        }
        String result = victory ? "WIN" : "LOSS";
        String durationStr = formatDuration(durationMs);
        List<String> team = teamSnapshot != null ? teamSnapshot : List.of("?");

        List<String> opponents = opponentsSeen.isEmpty() ? List.of("?") : new ArrayList<>(opponentsSeen);

        String line = String.format("[%s] %s vs %-20s | %s | Team: %s | Opponents: %s",
                LocalDateTime.now().format(TIMESTAMP_FMT),
                result,
                trainerName,
                durationStr,
                String.join(", ", team),
                String.join(", ", opponents));

        writeLine(line);
        AutoQiqiClient.logDebug("TowerLog", line);

        // Show summary in chat
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            String color = victory ? "§a" : "§c";
            mc.player.sendMessage(
                    net.minecraft.text.Text.literal("§e[Tower]§r " + color + result + "§r vs " + trainerName
                            + " §7(" + durationStr + ")"),
                    false);
        }

        // Reset
        accumulatedMs = 0;
        segmentStartMs = 0;
        timerRunning = false;
        trainerName = null;
        teamSnapshot = null;
        opponentsSeen.clear();
    }

    /** Capture current party as a list of "Species Lv.X" strings. */
    private List<String> captureTeam() {
        List<String> team = new ArrayList<>();
        try {
            var party = CobblemonClient.INSTANCE.getStorage().getParty();
            if (party == null) return team;
            @SuppressWarnings("unchecked")
            List<Pokemon> slots = (List<Pokemon>) party.getClass().getMethod("getSlots").invoke(party);
            for (Pokemon p : slots) {
                if (p == null) continue;
                String species = p.getSpecies().getName();
                int level = p.getLevel();
                team.add(species + " Lv." + level);
            }
        } catch (Exception e) {
            AutoQiqiClient.logDebug("TowerLog", "Failed to capture team: " + e.getMessage());
        }
        return team;
    }

    private void writeLine(String line) {
        try {
            Path logDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("auto-qiqi-logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve(LOG_FILE_NAME);
            try (BufferedWriter writer = Files.newBufferedWriter(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            AutoQiqiClient.logDebug("TowerLog", "Failed to write log: " + e.getMessage());
        }
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min > 0) {
            return min + "m " + sec + "s";
        }
        return sec + "s";
    }
}
