package com.cobblemoon.autoqiqi.common;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HumanDelayTest {

    @RepeatedTest(50)
    void guiClickTicksWithinRange() {
        int ticks = HumanDelay.guiClickTicks();
        assertTrue(ticks >= 24 && ticks <= 60,
                "guiClickTicks should be 24..60 but was " + ticks);
    }

    @RepeatedTest(50)
    void actionCooldownTicksWithinRange() {
        int ticks = HumanDelay.actionCooldownTicks();
        assertTrue(ticks >= 120 && ticks <= 280,
                "actionCooldownTicks should be 120..280 but was " + ticks);
    }

    @RepeatedTest(50)
    void commandDelayMsWithinRange() {
        int ms = HumanDelay.commandDelayMs(500, 4000);
        assertTrue(ms >= 500 && ms <= 4000,
                "commandDelayMs(500,4000) should be 500..4000 but was " + ms);
    }

    @Test
    void commandDelayMsClampsNegativeMin() {
        int ms = HumanDelay.commandDelayMs(-100, 200);
        assertTrue(ms >= 0, "Should clamp negative min to 0, got " + ms);
    }

    @Test
    void commandDelayMsHandlesInvertedRange() {
        // When max < min, the method adjusts max = min+1
        int ms = HumanDelay.commandDelayMs(500, 100);
        assertTrue(ms >= 500 && ms <= 501,
                "With inverted range, should clamp to min..min+1, got " + ms);
    }
}
