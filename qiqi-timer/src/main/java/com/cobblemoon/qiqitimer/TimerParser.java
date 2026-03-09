package com.cobblemoon.qiqitimer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses timer strings from chat (e.g. "Next legendary in 5 minutes and 10 seconds") into seconds.
 */
public final class TimerParser {
    private TimerParser() {}

    /**
     * Parse using config patterns first, then generic fallback.
     */
    public static Long parse(String message, QiqiTimerConfig config) {
        if (config != null) {
            try {
                if (config.timerPattern != null && !config.timerPattern.isEmpty()) {
                    Matcher m = Pattern.compile(config.timerPattern).matcher(message);
                    if (m.find()) {
                        long minutes = Long.parseLong(m.group(1));
                        long seconds = Long.parseLong(m.group(2));
                        return minutes * 60 + seconds;
                    }
                }
                if (config.timerPatternSecondsOnly != null && !config.timerPatternSecondsOnly.isEmpty()) {
                    Matcher m = Pattern.compile(config.timerPatternSecondsOnly).matcher(message);
                    if (m.find()) return Long.parseLong(m.group(1));
                }
            } catch (Exception ignored) { /* fall through */ }
        }
        return parseGeneric(message);
    }

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
        if (m3.find()) return Long.parseLong(m3.group(1));

        return null;
    }

    public static String stripFormatting(String message) {
        if (message == null) return "";
        return message.replaceAll("§[0-9a-fk-or]", "").trim();
    }
}
