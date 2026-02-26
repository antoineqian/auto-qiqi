package com.cobblemoon.autoqiqi.battle;

public enum BattleMode {
    OFF, BERSERK, ROAMING, TRAINER;

    public BattleMode next() {
        return switch (this) {
            case OFF -> BERSERK;
            case BERSERK -> ROAMING;
            case ROAMING -> TRAINER;
            case TRAINER -> OFF;
        };
    }

    public String displayName() {
        return switch (this) {
            case OFF -> "OFF";
            case BERSERK -> "Berserk";
            case ROAMING -> "Roaming";
            case TRAINER -> "Trainer";
        };
    }

    public static BattleMode fromString(String s) {
        if (s == null) return OFF;
        String upper = s.toUpperCase();
        if ("TARGETED".equals(upper)) return ROAMING;
        try {
            return valueOf(upper);
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }
}
