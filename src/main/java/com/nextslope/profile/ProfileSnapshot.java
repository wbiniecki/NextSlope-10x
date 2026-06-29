package com.nextslope.profile;

import java.util.Set;

import com.nextslope.resort.DifficultyMix;

/**
 * An immutable, read-only view of the four preference axes the recommendation engine consumes. Lets
 * the recommender read raw profile values without leaking the JPA entity or the web form. Region is
 * the empty set when "any region" (no filter).
 *
 * @param experienceLevel self-rated experience
 * @param difficultyBand chosen difficulty preset
 * @param noveltyPreference new-only vs revisit-okay
 * @param regionCountries selected countries; empty = any region
 */
public record ProfileSnapshot(
		ExperienceLevel experienceLevel,
		DifficultyBand difficultyBand,
		NoveltyPreference noveltyPreference,
		Set<String> regionCountries) {

	/** The canonical easy/medium/hard triple for the chosen band. */
	public DifficultyMix preferredMix() {
		return difficultyBand.toMix();
	}

	/** Whether the user constrained the result to specific regions. */
	public boolean hasRegionFilter() {
		return regionCountries != null && !regionCountries.isEmpty();
	}
}
