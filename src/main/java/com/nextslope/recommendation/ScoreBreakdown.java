package com.nextslope.recommendation;

/**
 * Per-axis alignments plus the combined soft-score for one candidate resort. Alignments are in
 * {@code [0,1]} (1 = perfect fit); {@code score} is the weighted blend the ranking sorts on.
 *
 * @param alignDiff difficulty-mix alignment ({@code 1 − L1(prefMix, resortMix)/200})
 * @param alignExp experience alignment ({@code 1 − |hardnessIndex − target|})
 * @param score the weighted combined score
 */
public record ScoreBreakdown(double alignDiff, double alignExp, double score) {
}
