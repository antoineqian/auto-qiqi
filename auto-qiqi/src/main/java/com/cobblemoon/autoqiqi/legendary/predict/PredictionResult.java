package com.cobblemoon.autoqiqi.legendary.predict;

import java.util.List;

/**
 * Prediction output for a single home: which legendaries can spawn there
 * and the expected value based on spawn weights and pokemon values.
 */
public record PredictionResult(
        HomeDefinition home,
        double expectedValue,
        List<MatchedSpawn> matchedSpawns,
        long predictedTimeOfDay,
        long remainingSeconds
) implements Comparable<PredictionResult> {

    @Override
    public int compareTo(PredictionResult other) {
        // Descending by EV, then ascending by remaining seconds
        int cmp = Double.compare(other.expectedValue, this.expectedValue);
        if (cmp != 0) return cmp;
        return Long.compare(this.remainingSeconds, other.remainingSeconds);
    }

    public record MatchedSpawn(
            String pokemonName,
            double value,
            int weight,
            double probability,
            double contribution
    ) {}
}
