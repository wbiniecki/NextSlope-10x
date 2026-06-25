package com.nextslope.resort;

/**
 * Display-only easy/medium/hard split (whole percentages summing to 100) derived
 * from a resort's three difficulty slope counts. Not persisted.
 */
public record DifficultyMix(int easy, int medium, int hard) {
}
