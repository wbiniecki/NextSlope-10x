package com.nextslope.recommendation;

import org.springframework.stereotype.Component;

import com.nextslope.profile.ProfileSnapshot;
import com.nextslope.resort.Resort;

import lombok.RequiredArgsConstructor;

/**
 * Builds the one-line, truthful "why this matched you" clause for a recommended resort. A clause
 * naming an axis is emitted only when the user actually set that axis and its alignment clears the
 * configured threshold; the strongest qualifying axis wins. When nothing qualifies, a truthful generic
 * fallback is used — never an unearned difficulty-mix claim.
 */
@Component
@RequiredArgsConstructor
public class RationaleBuilder {

	private final ScoringConfig config;

	/** A truthful one-line rationale for {@code resort} given its {@code breakdown} and the profile. */
	public String build(Resort resort, ScoreBreakdown breakdown, ProfileSnapshot profile) {
		double threshold = config.rationaleAlignmentThreshold();

		// Axes are weighed in a fixed priority order (region, difficulty, experience) so equal
		// alignments break deterministically; only axes the user set and that clear the threshold compete.
		String best = null;
		double bestAlignment = Double.NEGATIVE_INFINITY;

		if (profile.hasRegionFilter() && profile.regionCountries().contains(resort.getCountry())) {
			// A satisfied region hard filter is a perfect, truthful match.
			best = "a strong fit in " + resort.getCountry() + ", one of your selected regions";
			bestAlignment = 1.0;
		}

		if (breakdown.alignDiff() >= threshold && breakdown.alignDiff() > bestAlignment) {
			best = "its run difficulty matches your '" + profile.difficultyBand().getLabel() + "' preference";
			bestAlignment = breakdown.alignDiff();
		}

		if (breakdown.alignExp() >= threshold && breakdown.alignExp() > bestAlignment) {
			best = "well suited to " + profile.experienceLevel().getLabel() + " skiers";
			bestAlignment = breakdown.alignExp();
		}

		if (best != null) {
			return best;
		}

		return profile.hasRegionFilter()
				? "the closest available match in your selected regions"
				: "one of the closest matches to your overall preferences";
	}
}
