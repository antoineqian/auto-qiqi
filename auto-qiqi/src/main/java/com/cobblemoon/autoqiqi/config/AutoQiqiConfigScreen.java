package com.cobblemoon.autoqiqi.config;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.biome.BiomeDiscoveryEngine;
import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.BattleMode;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.legendary.PokemonWalker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class AutoQiqiConfigScreen extends Screen {

    private BattleMode selectedMode;
    private ButtonWidget modeButton;
    private ButtonWidget autoHomesButton;
    private ButtonWidget allHopSwitchButton;
    private ButtonWidget offToAllSwitchButton;
    private ButtonWidget tpaAltButton;
    private ButtonWidget biomeDiscoveryButton;

    public AutoQiqiConfigScreen() {
        super(Text.literal("Auto-Qiqi Configuration"));
        this.selectedMode = AutoBattleEngine.get().getMode();
    }

    @Override
    protected void init() {
        super.init();

        modeButton = ButtonWidget.builder(
                Text.literal("Mode: " + selectedMode.displayName()),
                btn -> cycleMode()
        ).dimensions(width / 2 - 100, 30, 200, 20).build();
        addDrawableChild(modeButton);

        AutoQiqiConfig config = AutoQiqiConfig.get();

        autoHomesButton = ButtonWidget.builder(
                Text.literal("Auto-Hop: " + autohopModeLabel(config.autohopMode)),
                btn -> cycleAutoHopMode()
        ).dimensions(width / 2 - 100, 54, 200, 20).build();
        addDrawableChild(autoHomesButton);

        allHopSwitchButton = ButtonWidget.builder(
                Text.literal("AllHop switch: " + allHopSwitchLabel(config.autohopSwitchToAllHour)),
                btn -> cycleAllHopSwitchHour()
        ).dimensions(width / 2 - 100, 78, 200, 20).build();
        addDrawableChild(allHopSwitchButton);

        offToAllSwitchButton = ButtonWidget.builder(
                Text.literal("Off→All switch: " + allHopSwitchLabel(config.autohopSwitchOffToAllHour)),
                btn -> cycleOffToAllSwitchHour()
        ).dimensions(width / 2 - 100, 102, 200, 20).build();
        addDrawableChild(offToAllSwitchButton);

        tpaAltButton = ButtonWidget.builder(
                Text.literal("TP Alt: " + (config.autohopTpaEnabled ? "ON" : "OFF")),
                btn -> toggleTpaAlt()
        ).dimensions(width / 2 - 100, 126, 200, 20).build();
        addDrawableChild(tpaAltButton);

        biomeDiscoveryButton = ButtonWidget.builder(
                Text.literal("Biome Discovery: " + (BiomeDiscoveryEngine.get().isEnabled() ? "ON" : "OFF")),
                btn -> toggleBiomeDiscovery()
        ).dimensions(width / 2 - 100, 150, 200, 20).build();
        addDrawableChild(biomeDiscoveryButton);

        ButtonWidget doneButton = ButtonWidget.builder(
                Text.literal("Done"),
                btn -> close()
        ).dimensions(width / 2 - 50, height - 28, 100, 20).build();
        addDrawableChild(doneButton);
    }

    private void cycleMode() {
        BattleMode prev = selectedMode;
        selectedMode = selectedMode.next();
        modeButton.setMessage(Text.literal("Mode: " + selectedMode.displayName()));

        if (CaptureEngine.get().isActive() || PokemonWalker.get().isActive()) {
            CaptureEngine.get().stop();
            PokemonWalker.get().stop();
        }

        AutoBattleEngine engine = AutoBattleEngine.get();
        engine.setMode(selectedMode);
        if (selectedMode == BattleMode.OFF) engine.reset();

        AutoQiqiConfig.get().battleMode = selectedMode.name();
        AutoQiqiConfig.save();

        AutoQiqiClient.logDebug("Battle", "Config screen mode: " + prev + " -> " + selectedMode);
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logEvent("MODE_CHANGE",
                "Battle mode: " + prev + " -> " + selectedMode + " (config screen)");
    }

    private void cycleAutoHopMode() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        config.autohopMode = switch (config.autohopMode) {
            case "auto" -> "all";
            case "all" -> "off";
            default -> "auto";
        };
        autoHomesButton.setMessage(Text.literal("Auto-Hop: " + autohopModeLabel(config.autohopMode)));
        AutoQiqiConfig.save();

        // Clear the runtime disabled flag when the user selects a non-off mode.
        // The disabled flag is set by legendary spawns and is separate from the config mode,
        // but changing the mode to auto/all is an explicit user intent to enable auto-hop.
        var hop = com.cobblemoon.autoqiqi.legendary.autohop.AutoHopEngine.get();
        if (!"off".equals(config.autohopMode) && hop.isDisabled()) {
            hop.setDisabled(false);
        }

        AutoQiqiClient.logDebug("Config", "autohopMode = " + config.autohopMode);
    }

    private void cycleAllHopSwitchHour() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        // Cycle: -1 → 2:00 → 2:30 → 3:00 → 3:30 → 4:00 → -1
        // Stored as minutes past midnight for half-hour granularity
        int cur = config.autohopSwitchToAllHour;
        if (cur < 120) {
            config.autohopSwitchToAllHour = 120;  // 2:00
        } else if (cur >= 240) {
            config.autohopSwitchToAllHour = -1;
        } else {
            config.autohopSwitchToAllHour = cur + 30;
        }
        allHopSwitchButton.setMessage(Text.literal("AllHop switch: " + allHopSwitchLabel(config.autohopSwitchToAllHour)));
        AutoQiqiConfig.save();
        AutoQiqiClient.logDebug("Config", "autohopSwitchToAllHour = " + config.autohopSwitchToAllHour);
    }

    private void cycleOffToAllSwitchHour() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        int cur = config.autohopSwitchOffToAllHour;
        if (cur < 120) {
            config.autohopSwitchOffToAllHour = 120;  // 2:00
        } else if (cur >= 240) {
            config.autohopSwitchOffToAllHour = -1;
        } else {
            config.autohopSwitchOffToAllHour = cur + 30;
        }
        offToAllSwitchButton.setMessage(Text.literal("Off→All switch: " + allHopSwitchLabel(config.autohopSwitchOffToAllHour)));
        AutoQiqiConfig.save();
        AutoQiqiClient.logDebug("Config", "autohopSwitchOffToAllHour = " + config.autohopSwitchOffToAllHour);
    }

    private static String allHopSwitchLabel(int minutesPastMidnight) {
        if (minutesPastMidnight < 0) return "OFF";
        return String.format("%02d:%02d", minutesPastMidnight / 60, minutesPastMidnight % 60);
    }

    private static String autohopModeLabel(String mode) {
        return switch (mode) {
            case "auto" -> "auto* homes";
            case "all" -> "all homes";
            case "off" -> "OFF";
            default -> mode;
        };
    }

    private void toggleTpaAlt() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        config.autohopTpaEnabled = !config.autohopTpaEnabled;
        tpaAltButton.setMessage(Text.literal("TP Alt: " + (config.autohopTpaEnabled ? "ON" : "OFF")));
        AutoQiqiConfig.save();
        AutoQiqiClient.logDebug("Config", "autohopTpaEnabled = " + config.autohopTpaEnabled);
    }

    private void toggleBiomeDiscovery() {
        BiomeDiscoveryEngine engine = BiomeDiscoveryEngine.get();
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (engine.isEnabled()) {
            engine.stop();
            config.biomeDiscoveryEnabled = false;
        } else {
            engine.start();
            config.biomeDiscoveryEnabled = true;
        }
        biomeDiscoveryButton.setMessage(Text.literal("Biome Discovery: " + (engine.isEnabled() ? "ON" : "OFF")));
        AutoQiqiConfig.save();
        AutoQiqiClient.logDebug("Config", "biomeDiscoveryEnabled = " + config.biomeDiscoveryEnabled);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);
    }
}
