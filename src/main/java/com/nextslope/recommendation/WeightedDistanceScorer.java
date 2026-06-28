package com.nextslope.recommendation;

import org.springframework.stereotype.Component;

import com.nextslope.profile.ProfileSnapshot;
import com.nextslope.resort.DifficultyMix;

import lombok.RequiredArgsConstructor;

/**
 * Approach A scorer: weighted normalized distance. Difficulty alignment is one minus the normalized
 * L1 distance between the preferred and resort mixes; experience alignment compares a hardness index
 * derived from the resort mix against the profile's per-level target. All knobs come from
 * {@link ScoringConfig}.
 */
@Component
@RequiredArgsConstructor
public class WeightedDistanceScorer implements Scorer {

	private final ScoringConfig config;

	@Override
	public ScoreBreakdown score(DifficultyMix resortMix, ProfileSnapshot profile) {
		DifficultyMix preferred = profile.preferredMix();
		int l1 = Math.abs(preferred.easy() - resortMix.easy())
				+ Math.abs(preferred.medium() - resortMix.medium())
				+ Math.abs(preferred.hard() - resortMix.hard());
		double alignDiff = 1.0 - l1 / 200.0;

		double hardnessIndex = (0.5 * resortMix.medium() + resortMix.hard()) / 100.0;
		double target = config.hardnessTarget(profile.experienceLevel());
		double alignExp = 1.0 - Math.abs(hardnessIndex - target);

		double score = config.weightDiff() * alignDiff + config.weightExp() * alignExp;
		return new ScoreBreakdown(alignDiff, alignExp, score);
	}
}
