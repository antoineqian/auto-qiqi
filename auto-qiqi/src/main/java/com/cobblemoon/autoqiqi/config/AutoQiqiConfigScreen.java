package com.cobblemoon.autoqiqi.config;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
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
    private ButtonWidget tpaAltButton;

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

        tpaAltButton = ButtonWidget.builder(
                Text.literal("TP Alt: " + (config.autohopTpaEnabled ? "ON" : "OFF")),
                btn -> toggleTpaAlt()
        ).dimensions(width / 2 - 100, 78, 200, 20).build();
        addDrawableChild(tpaAltButton);

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
        AutoQiqiClient.logDebug("Config", "autohopMode = " + config.autohopMode);
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

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);
    }
}
