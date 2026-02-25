package com.cobblemoon.autoqiqi.config;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.BattleMode;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.legendary.PokemonWalker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class AutoQiqiConfigScreen extends Screen {

    private BattleMode selectedMode;
    private ButtonWidget modeButton;
    private final List<WorldRow> worldRows = new ArrayList<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;

    private static final int ROW_HEIGHT = 28;
    private static final int TOP_MARGIN = 60;
    private static final int BOTTOM_MARGIN = 40;

    public AutoQiqiConfigScreen() {
        super(Text.literal("Auto-Qiqi Configuration"));
        this.selectedMode = AutoBattleEngine.get().getMode();
    }

    @Override
    protected void init() {
        super.init();
        worldRows.clear();

        modeButton = ButtonWidget.builder(
                Text.literal("Mode: " + selectedMode.displayName()),
                btn -> cycleMode()
        ).dimensions(width / 2 - 100, 30, 200, 20).build();
        addDrawableChild(modeButton);

        AutoQiqiConfig config = AutoQiqiConfig.get();
        List<String> worlds = config.worldNames;

        for (int i = 0; i < worlds.size(); i++) {
            String worldName = worlds.get(i);
            String currentHome = config.getHomeCommand(worldName);
            if (currentHome == null) currentHome = "";

            int fieldY = TOP_MARGIN + i * ROW_HEIGHT;
            TextFieldWidget field = new TextFieldWidget(
                    textRenderer, width / 2, fieldY, 140, 18, Text.literal("home"));
            field.setMaxLength(64);
            field.setText(currentHome);
            addDrawableChild(field);
            worldRows.add(new WorldRow(worldName, field));
        }

        int contentHeight = TOP_MARGIN + worlds.size() * ROW_HEIGHT + BOTTOM_MARGIN;
        maxScroll = Math.max(0, contentHeight - height);

        ButtonWidget doneButton = ButtonWidget.builder(
                Text.literal("Done"),
                btn -> close()
        ).dimensions(width / 2 - 50, height - 28, 100, 20).build();
        addDrawableChild(doneButton);

        updateScrollPositions();
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

        AutoQiqiClient.log("Battle", "Config screen mode: " + prev + " -> " + selectedMode);
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logEvent("MODE_CHANGE",
                "Battle mode: " + prev + " -> " + selectedMode + " (config screen)");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);

        for (int i = 0; i < worldRows.size(); i++) {
            WorldRow row = worldRows.get(i);
            int labelY = TOP_MARGIN + i * ROW_HEIGHT - scrollOffset + 5;
            if (labelY < 24 || labelY > height - BOTTOM_MARGIN) continue;
            int labelX = width / 2 - 160;
            context.drawTextWithShadow(textRenderer, row.worldName, labelX, labelY, 0xFFFFAA);

            int tagX = width / 2 + 145;
            String homeText = row.field.getText().trim();
            if (!homeText.isEmpty()) {
                context.drawTextWithShadow(textRenderer, "/home", tagX, labelY, 0x88FF88);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (verticalAmount * 10)));
        updateScrollPositions();
        return true;
    }

    private void updateScrollPositions() {
        for (int i = 0; i < worldRows.size(); i++) {
            WorldRow row = worldRows.get(i);
            int y = TOP_MARGIN + i * ROW_HEIGHT - scrollOffset;
            row.field.setY(y);
            boolean visible = y >= 24 && y + 18 <= height - BOTTOM_MARGIN;
            row.field.visible = visible;
            row.field.active = visible;
        }
    }

    @Override
    public void close() {
        saveHomeSettings();
        super.close();
    }

    private void saveHomeSettings() {
        AutoQiqiConfig config = AutoQiqiConfig.get();

        for (WorldRow row : worldRows) {
            String homeValue = row.field.getText().trim();
            String key = row.worldName.toLowerCase();
            if (!homeValue.isEmpty()) {
                config.homeWorlds.put(key, homeValue);
            } else {
                config.homeWorlds.remove(key);
            }
        }

        AutoQiqiConfig.save();
        AutoQiqiClient.log("Config", "Saved home settings from config screen");
    }

    private record WorldRow(String worldName, TextFieldWidget field) {}
}
