package com.cobblemoon.autoqiqi;

import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.BattleMode;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.battle.TrainerBattleEngine;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.*;
import com.cobblemoon.autoqiqi.mine.GoldMiningEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;

import java.util.Collection;

/**
 * Unified HUD rendering for all Auto-Qiqi features.
 * Top-left:    feature status lines (battle, walk)
 * Bottom-right: legendary world timers
 */
public class AutoQiqiHud {

    public static void render(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.getDebugHud().shouldShowDebugHud()) return;

        renderStatusHud(context, client);
        renderBattleAdvisor(context, client);
        renderLegendaryHud(context, client);
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
                String action;
                if (engine.isTargetForCapture()) {
                    action = "Capturing";
                } else if (engine.isWalking()) {
                    action = "Walking";
                } else {
                    action = "Engaging";
                }
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

        // Walk status
        if (AutoQiqiClient.isWalkEnabled()) {
            context.drawText(tr, Text.literal("§a[Walk ON]"), 4, y, 0xFFFFFF, true);
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

        // Mining status
        GoldMiningEngine mining = GoldMiningEngine.get();
        String mineStatus = mining.getStatusDisplay();
        String durDisplay = mining.getDurabilityDisplay();
        if (mineStatus != null) {
            String durSuffix = durDisplay != null ? " " + durDisplay : "";
            context.drawText(tr, Text.literal("§6" + mineStatus + durSuffix), 4, y, 0xFFFFFF, true);
            y += 12;
        } else if (mining.isInNether() && AutoQiqiConfig.get().goldMiningEnabled) {
            int mined = mining.getSessionOresMined();
            String durSuffix = durDisplay != null ? " " + durDisplay : "";
            if (mined > 0) {
                context.drawText(tr, Text.literal("§7[Mine] §6" + mined + " ores" + durSuffix), 4, y, 0xFFFFFF, true);
            }
        }
    }

    // ========================
    // Top-right: battle advisor (any battle)
    // ========================

    private static void renderBattleAdvisor(DrawContext context, MinecraftClient client) {
        TrainerBattleEngine.AdvisorInfo info = TrainerBattleEngine.get().getAdvisorInfo();
        if (info == null) return;

        TextRenderer tr = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();
        int margin = 5;
        int lineHeight = 12;
        int y = 4;

        String line1 = "§7vs §f" + info.opponentName() + " §8(" + info.opponentTypesDisplay() + ")";
        int w1 = tr.getWidth(line1.replaceAll("§.", ""));
        int x1 = screenWidth - w1 - margin;
        drawBg(context, tr, line1, x1, y, 0xFFFFFFFF);
        y += lineHeight;

        if (info.hasBetterOption()) {
            String effColor = info.bestEffectiveness() >= 2.0 ? "§a"
                    : info.bestEffectiveness() >= 1.0 ? "§e" : "§c";
            String line2 = "§6>> §f" + info.bestName()
                    + " §7(" + info.bestTypesDisplay() + ") "
                    + effColor + info.bestEffectiveness() + "x";
            int w2 = tr.getWidth(line2.replaceAll("§.", ""));
            int x2 = screenWidth - w2 - margin;
            drawBg(context, tr, line2, x2, y, 0xFFFFFF55);
        } else {
            String line2 = "§a" + info.currentName() + " §7- best matchup";
            int w2 = tr.getWidth(line2.replaceAll("§.", ""));
            int x2 = screenWidth - w2 - margin;
            drawBg(context, tr, line2, x2, y, 0xFF55FF55);
        }
    }

    // ========================
    // Bottom-right: legendary timers
    // ========================

    private static void renderLegendaryHud(DrawContext context, MinecraftClient client) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (!config.legendaryEnabled || !config.legendaryHudVisible) return;

        TextRenderer tr = client.textRenderer;
        WorldTracker tracker = WorldTracker.get();
        tracker.refreshWorldList();

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int lineHeight = 12;
        int margin = 5;

        Collection<WorldTimerData> timers = tracker.getAllTimers();
        String currentWorld = tracker.getCurrentWorld();

        if (!config.legendaryAutoSwitch) {
            String offText = "[Legendary OFF]";
            int w = tr.getWidth(offText);
            int x = screenWidth - w - margin;
            int y = screenHeight - lineHeight - margin;
            drawBg(context, tr, offText, x, y, 0xFFFF5555);
            return;
        }

        int totalLines = timers.size();
        AutoSwitchEngine engine = AutoSwitchEngine.get();
        String stateDisplay = engine.getStateDisplay();
        boolean showStatus = engine.isPaused()
                || stateDisplay.contains("position")
                || stateDisplay.contains("Envoi")
                || stateDisplay.contains("Lecture");
        if (showStatus) totalLines++;

        PokemonWalker walker = PokemonWalker.get();
        String walkStatus = walker.getStatusDisplay();
        boolean showWalk = walkStatus != null;
        if (showWalk) totalLines++;

        int bottomY = screenHeight - margin;
        int y = bottomY - (totalLines * lineHeight);

        if (showWalk) {
            int w = tr.getWidth(walkStatus);
            int x = screenWidth - w - margin;
            drawBg(context, tr, walkStatus, x, y, 0xFF55FFFF);
            y += lineHeight;
        }

        if (showStatus) {
            int statusColor = engine.isPaused() ? 0xFFFF55FF : 0xFFAAAAAA;
            int w = tr.getWidth(stateDisplay);
            int x = screenWidth - w - margin;
            drawBg(context, tr, stateDisplay, x, y, statusColor);
            y += lineHeight;
        }

        for (WorldTimerData data : timers) {
            String worldName = data.getWorldName();
            String shortName = getShortName(worldName);
            String time = data.getFormattedTime();
            boolean isCurrent = worldName.equals(currentWorld);

            int color;
            String prefix;

            if (isCurrent) {
                prefix = "-> ";
                color = 0xFFFFFFFF;
            } else if (data.isTimerKnown() && data.getEstimatedRemainingSeconds() <= config.switchBeforeSeconds) {
                prefix = " ! ";
                color = config.hudColorUrgent;
            } else {
                prefix = "   ";
                color = config.hudColor;
            }

            String line = prefix + shortName + " " + time;
            int w = tr.getWidth(line);
            int x = screenWidth - w - margin;
            drawBg(context, tr, line, x, y, color);
            y += lineHeight;
        }
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
