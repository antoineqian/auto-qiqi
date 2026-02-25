package com.cobblemoon.autoqiqi.battle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class BattleModeTest {

    @Test
    void nextCyclesThroughAllModes() {
        assertEquals(BattleMode.BERSERK, BattleMode.OFF.next());
        assertEquals(BattleMode.ROAMING, BattleMode.BERSERK.next());
        assertEquals(BattleMode.TRAINER, BattleMode.ROAMING.next());
        assertEquals(BattleMode.TEST, BattleMode.TRAINER.next());
        assertEquals(BattleMode.OFF, BattleMode.TEST.next());
    }

    @Test
    void nextFullCycleReturnsToStart() {
        BattleMode mode = BattleMode.OFF;
        mode = mode.next().next().next().next().next();
        assertEquals(BattleMode.OFF, mode);
    }

    @Test
    void displayNameMatchesExpected() {
        assertEquals("OFF", BattleMode.OFF.displayName());
        assertEquals("Berserk", BattleMode.BERSERK.displayName());
        assertEquals("Roaming", BattleMode.ROAMING.displayName());
        assertEquals("Trainer", BattleMode.TRAINER.displayName());
        assertEquals("Test (Lv40+)", BattleMode.TEST.displayName());
    }

    @ParameterizedTest
    @CsvSource({
            "OFF, OFF",
            "off, OFF",
            "BERSERK, BERSERK",
            "berserk, BERSERK",
            "ROAMING, ROAMING",
            "roaming, ROAMING",
    })
    void fromStringParsesValidModes(String input, BattleMode expected) {
        assertEquals(expected, BattleMode.fromString(input));
    }

    @Test
    void fromStringMapsLegacyTargetedToRoaming() {
        assertEquals(BattleMode.ROAMING, BattleMode.fromString("TARGETED"));
        assertEquals(BattleMode.ROAMING, BattleMode.fromString("targeted"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "123", "BERSERKER", "  "})
    void fromStringDefaultsToOffForInvalidInput(String input) {
        assertEquals(BattleMode.OFF, BattleMode.fromString(input));
    }
}
