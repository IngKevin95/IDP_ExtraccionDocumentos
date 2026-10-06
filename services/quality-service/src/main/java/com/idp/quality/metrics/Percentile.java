package com.idp.quality.metrics;

import java.util.Arrays;
import java.util.OptionalLong;

/** Percentil por rango mas cercano; sin muestra minima no publica valor (evita p95 sobre pocos datos). */
public final class Percentile {

    private Percentile() {
    }

    public static OptionalLong nearestRank(long[] values, double percentile, int minSample) {
        if (values.length == 0 || values.length < minSample) {
            return OptionalLong.empty();
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return OptionalLong.of(sorted[Math.max(1, Math.min(sorted.length, rank)) - 1]);
    }
}
