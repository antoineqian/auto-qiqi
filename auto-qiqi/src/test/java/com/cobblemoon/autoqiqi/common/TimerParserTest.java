package com.cobblemoon.autoqiqi.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class TimerParserTest {

    // Default patterns from AutoQiqiConfig
    private static final Pattern TIMER_PATTERN = Pattern.compile(
            "(?i)(\\d+)\\s*minutes?\\s*(?:and|et)\\s*(\\d+)\\s*seconds?"
    );
    private static final Pattern SECONDS_ONLY = Pattern.compile(
            "(?i)(\\d+)\\s*seconds?"
    );

    // ── Primary pattern: "X minutes and/et Y seconds" ──

    @ParameterizedTest
    @CsvSource({
            "'Le prochain légendaire apparaîtra dans 15 minutes et 30 seconds', 930",
            "'Next legendary in 5 minutes and 10 seconds', 310",
            "'1 minute et 0 seconds', 60",
            "'0 minutes et 45 seconds', 45",
            "'59 minutes and 59 seconds', 3599",
    })
    void parsesMinutesAndSeconds(String message, long expectedSeconds) {
        assertEquals(expectedSeconds, TimerParser.parse(message, TIMER_PATTERN, SECONDS_ONLY));
    }

    // ── Seconds-only pattern ──

    @ParameterizedTest
    @CsvSource({
            "'45 seconds remaining', 45",
            "'Only 1 second left', 1",
            "'Timer: 120 seconds', 120",
    })
    void parsesSecondsOnly(String message, long expectedSeconds) {
        assertEquals(expectedSeconds, TimerParser.parse(message, TIMER_PATTERN, SECONDS_ONLY));
    }

    // ── Generic fallback: h/m/s shorthand ──

    @ParameterizedTest
    @CsvSource({
            "'1h 30m 15s', 5415",
            "'2h 0min 0sec', 7200",
            "'1heure 10min 5sec', 4205",
    })
    void parsesGenericHourMinSec(String message, long expectedSeconds) {
        assertEquals(expectedSeconds, TimerParser.parseGeneric(message));
    }

    @ParameterizedTest
    @CsvSource({
            "'25m 30s', 1530",
            "'5min 0sec', 300",
            "'10min 45sec', 645",
    })
    void parsesGenericMinSec(String message, long expectedSeconds) {
        assertEquals(expectedSeconds, TimerParser.parseGeneric(message));
    }

    @ParameterizedTest
    @CsvSource({
            "'90s', 90",
            "'30sec', 30",
            "'1s', 1",
    })
    void parsesGenericSecOnly(String message, long expectedSeconds) {
        assertEquals(expectedSeconds, TimerParser.parseGeneric(message));
    }

    // ── No match ──

    @ParameterizedTest
    @ValueSource(strings = {
            "Hello world",
            "No timer here",
            "minutes seconds",
            "abc def",
    })
    void returnsNullForNoTimerFound(String message) {
        assertNull(TimerParser.parse(message, TIMER_PATTERN, SECONDS_ONLY));
    }

    @Test
    void parseWithNullPatternsStillTriesGeneric() {
        assertEquals(90L, TimerParser.parse("1min 30sec", null, null));
    }

    // ── stripFormatting ──

    @Test
    void stripFormattingRemovesMinecraftCodes() {
        assertEquals("Hello World", TimerParser.stripFormatting("§aHello §lWorld"));
        assertEquals("test", TimerParser.stripFormatting("§1§2§3test"));
    }

    @Test
    void stripFormattingTrimsWhitespace() {
        assertEquals("hello", TimerParser.stripFormatting("  hello  "));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void stripFormattingHandlesNullAndEmpty(String input) {
        assertEquals("", TimerParser.stripFormatting(input));
    }

    @Test
    void stripFormattingPreservesNonFormattedText() {
        assertEquals("plain text 123", TimerParser.stripFormatting("plain text 123"));
    }
}
