package com.nextslope.recommendation;

import com.nextslope.resort.DifficultyMix;

/**
 * View-ready facts for one recommended resort plus its truthful rationale. Carries only what the
 * result fragment renders — never the JPA entity.
 *
 * @param id resort id
 * @param name resort name
 * @param country resort country
 * @param topLiftHeight highest point in metres
 * @param totalSlopes total number of slopes
 * @param totalLifts total number of lifts
 * @param difficultyMix derived easy/medium/hard split
 * @param rationale the one-line "why this matched you" clause
 */
public record ResortCard(
		Long id,
		String name,
		String country,
		Integer topLiftHeight,
		Integer totalSlopes,
		Integer totalLifts,
		DifficultyMix difficultyMix,
		String rationale) {
}
