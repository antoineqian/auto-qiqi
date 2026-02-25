package com.cobblemoon.autoqiqi.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses legendary event timer strings from chat messages into seconds.
 * Supports French-style timer formats (minutes/seconds, h/m/s shorthand).
 */
public final class TimerParser {
    private TimerParser() {}

    /**
     * Attempts to parse a timer value from the message using the given patterns.
     * Returns null if no timer format is found.
     */
    public static Long parse(String message, Pattern timerPattern, Pattern timerPatternSecondsOnly) {
        if (timerPattern != null) {
            Matcher matcher = timerPattern.matcher(message);
            if (matcher.find()) {
                try {
                    long minutes = Long.parseLong(matcher.group(1));
                    long seconds = Long.parseLong(matcher.group(2));
                    return minutes * 60 + seconds;
                } catch (NumberFormatException e) { /* fall through */ }
            }
        }

        if (timerPatternSecondsOnly != null) {
            Matcher matcher = timerPatternSecondsOnly.matcher(message);
            if (matcher.find()) {
                try { return Long.parseLong(matcher.group(1)); }
                catch (NumberFormatException e) { /* fall through */ }
            }
        }

        return parseGeneric(message);
    }

    /**
     * Fallback parser that tries common timer formats: h/m/s, m/s, s-only.
     */
    public static Long parseGeneric(String message) {
        String lower = message.toLowerCase();

        Pattern hourMinSec = Pattern.compile("(\\d+)\\s*(?:h|heure).*?(\\d+)\\s*(?:m|min).*?(\\d+)\\s*(?:s|sec)");
        Matcher m1 = hourMinSec.matcher(lower);
        if (m1.find()) {
            return Long.parseLong(m1.group(1)) * 3600
                    + Long.parseLong(m1.group(2)) * 60
                    + Long.parseLong(m1.group(3));
        }

        Pattern minSec = Pattern.compile("(\\d+)\\s*(?:m|min).*?(\\d+)\\s*(?:s|sec)");
        Matcher m2 = minSec.matcher(lower);
        if (m2.find()) {
            return Long.parseLong(m2.group(1)) * 60 + Long.parseLong(m2.group(2));
        }

        Pattern secOnly = Pattern.compile("(\\d+)\\s*(?:s|sec)");
        Matcher m3 = secOnly.matcher(lower);
        if (m3.find()) {
            return Long.parseLong(m3.group(1));
        }

        return null;
    }

    /**
     * Strips Minecraft formatting codes (e.g. §a, §l) from a message.
     */
    public static String stripFormatting(String message) {
        if (message == null) return "";
        return message.replaceAll("§[0-9a-fk-or]", "").trim();
    }
}
