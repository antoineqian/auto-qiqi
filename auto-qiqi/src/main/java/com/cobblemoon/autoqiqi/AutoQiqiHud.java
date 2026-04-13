package com.cobblemoon.autoqiqi;

import com.cobblemoon.autoqiqi.battle.*;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.*;
import com.cobblemoon.autoqiqi.legendary.predict.LegendTrackerBridge;
import com.cobblemoon.autoqiqi.legendary.predict.PredictionResult;
import com.cobblemoon.autoqiqi.legendary.predict.SpawnPredictor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;

import java.util.*;

/**
 * Unified HUD rendering for all Auto-Qiqi features.
 * Top-left:    feature status lines (battle, walk)
 * Top-center: (reserved)
 * Bottom-right: legendary world timers
 */
public class AutoQiqiHud {

    public static void render(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.getDebugHud().shouldShowDebugHud()) return;

        renderStatusHud(context, client);
        renderPredictionHud(context, client);
    }

    // ========================
    // Top-left: feature status
    // ========================

    private static void renderStatusHud(DrawContext context, MinecraftClient client) {
        TextRenderer tr = client.textRenderer;
        int y = 4;

        // Battle status
        BattleMode battleMode = AutoQiqiClient.getBattleMode();
        if (battleMode != BattleMode.OFF) {
            AutoBattleEngine engine = AutoBattleEngine.get();
            Entity target = engine.getTarget();
            String modeTag;
            if (battleMode == BattleMode.ROAMING) {
                modeTag = "§c[Roaming]";
            } else if (battleMode == BattleMode.TRAINER) {
                modeTag = "§b[Trainer]";
            } else {
                modeTag = "§a[Berserk]";
            }
            String status;
            if (battleMode == BattleMode.TRAINER) {
                status = modeTag + " §7Ready (auto-fight)";
            } else if (target != null) {
                double dist = client.player.distanceTo(target);
                String action = engine.isWalking() ? "Walking" : "Engaging";
                status = modeTag + " §f" + action + " §7(" + String.format("%.1f", dist) + "m)";
            } else {
                status = modeTag + " §7Scanning...";
            }
            context.drawText(tr, Text.literal(status), 4, y, 0xFFFFFF, true);
            y += 12;
        }

        // Capture status
        CaptureEngine capture = CaptureEngine.get();
        if (capture.isActive()) {
            String capMsg = "§e[Capture] §f" + capture.getStatusMessage();
            context.drawText(tr, Text.literal(capMsg), 4, y, 0xFFFFFF, true);
            y += 12;
        }

        // Hunt timer
        if (AutoQiqiClient.isHuntActive()) {
            long ms = AutoQiqiClient.getHuntRemainingMs();
            long totalSec = ms / 1000;
            long h = totalSec / 3600;
            long m = (totalSec % 3600) / 60;
            long s = totalSec % 60;
            String timeStr = h > 0
                    ? String.format("%dh%02dm%02ds", h, m, s)
                    : String.format("%02dm%02ds", m, s);
            context.drawText(tr, Text.literal("§d[Hunt] §f" + timeStr), 4, y, 0xFFFFFF, true);
            y += 12;
        }

    }

    // ============================
    // Top-center: spawn predictions
    // ============================

    private static void renderPredictionHud(DrawContext context, MinecraftClient client) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (!config.predictionHudVisible) return;

        TextRenderer tr = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();
        int lineHeight = 12;
        int maxResults = config.predictionHudMaxResults;

        List<String> lines = new ArrayList<>();

        // Try LegendTracker bridge first (native predictions, guaranteed accurate)
        List<LegendTrackerBridge.HomePrediction> ltPredictions = LegendTrackerBridge.isAvailable()
                ? LegendTrackerBridge.getFullPredictions() : List.of();

        if (!ltPredictions.isEmpty()) {
            int count = Math.min(ltPredictions.size(), maxResults);
            for (int i = 0; i < count; i++) {
                LegendTrackerBridge.HomePrediction p = ltPredictions.get(i);
                String worldShort = getShortName(p.world());
                String evStr = String.format("%.1f", p.ev());

                // Sort pokemon by probability descending, show top 3
                StringBuilder spawns = new StringBuilder();
                int shown = 0;
                List<Map.Entry<String, Double>> sorted = new ArrayList<>(p.pokemonProbabilities().entrySet());
                sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                for (Map.Entry<String, Double> e : sorted) {
                    if (shown >= 3) break;
                    if (shown > 0) spawns.append(" ");
                    spawns.append(e.getKey()).append(" ").append(Math.round(e.getValue())).append("%");
                    shown++;
                }

                String confirmMark = p.needsConfirmation() ? " §c⚠" : "";
                String line = "§b#" + (i + 1) + " §f" + p.name() + " §7(" + worldShort + ") §eEV:" + evStr + confirmMark;
                if (shown > 0) line += "  §7" + spawns;
                lines.add(line);
            }
            lines.add(0, "§8[LegendTracker] §7" + ltPredictions.size() + " homes | "
                    + formatTime(LegendTrackerBridge.getRemainingSeconds()));
        } else {
            // Fallback: our own predictor
            List<PredictionResult> results = SpawnPredictor.get().getResults();
            if (results.isEmpty()) return;
            int count = Math.min(results.size(), maxResults);
            for (int i = 0; i < count; i++) {
                PredictionResult r = results.get(i);
                String worldShort = getShortName(r.home().world());
                String evStr = String.format("%.1f", r.expectedValue());

                StringBuilder spawns = new StringBuilder();
                int shown = 0;
                for (PredictionResult.MatchedSpawn m : r.matchedSpawns()) {
                    if (m.contribution() <= 0 || shown >= 3) break;
                    if (shown > 0) spawns.append(" ");
                    spawns.append(m.pokemonName()).append(" ").append(Math.round(m.probability() * 100)).append("%");
                    shown++;
                }

                String line = "§b#" + (i + 1) + " §f" + r.home().key() + " §7(" + worldShort + ") §eEV:" + evStr;
                if (shown > 0) line += "  §7" + spawns;
                lines.add(line);
            }
            lines.add(0, "§8[Predictor] §7" + results.size() + " homes");
        }

        if (lines.isEmpty()) return;

        int color = config.hudColor;
        int y = (int) (client.getWindow().getScaledHeight() * 0.10);
        for (String line : lines) {
            int w = tr.getWidth(line.replaceAll("§.", ""));
            int x = (screenWidth - w) / 2;
            drawBg(context, tr, line, x, y, color);
            y += lineHeight;
        }
    }

    private static String formatTime(long totalSeconds) {
        if (totalSeconds <= 0) return "§c--:--";
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        String color = totalSeconds < 60 ? "§c" : totalSeconds < 300 ? "§e" : "§a";
        return color + String.format("%d:%02d", m, s);
    }

    private static String getShortName(String worldName) {
        if (worldName == null) return "?";
        String lower = worldName.toLowerCase();
        if (lower.contains("ultra-lune")) return "UL";
        if (lower.contains("ultra-soleil")) return "US";
        if (lower.contains("lune")) return "L";
        if (lower.contains("soleil")) return "S";
        if (lower.contains("ressources") && lower.contains("nether")) return "R-N";
        if (lower.contains("ressources") && lower.contains("end")) return "R-E";
        if (lower.contains("ressources") && lower.contains("overworld")) return "R-OW";
        if (lower.contains("ressources")) return "R";
        return worldName.length() > 3 ? worldName.substring(0, 3) : worldName;
    }

    private static void drawBg(DrawContext context, TextRenderer tr,
                                String text, int x, int y, int color) {
        int width = tr.getWidth(text);
        context.fill(x - 2, y - 1, x + width + 2, y + 10, 0x88000000);
        context.drawText(tr, text, x, y, color, true);
    }
}
