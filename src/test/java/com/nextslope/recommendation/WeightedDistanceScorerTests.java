package com.nextslope.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nextslope.profile.DifficultyBand;
import com.nextslope.profile.ExperienceLevel;
import com.nextslope.profile.NoveltyPreference;
import com.nextslope.profile.ProfileSnapshot;
import com.nextslope.resort.DifficultyMix;

class WeightedDistanceScorerTests {

	private static final double EPS = 1e-9;

	private final WeightedDistanceScorer scorer = new WeightedDistanceScorer(ScoringConfig.defaults());

	private static ProfileSnapshot profile(ExperienceLevel experience, DifficultyBand band) {
		return new ProfileSnapshot(experience, band, NoveltyPreference.REVISIT_OKAY, Set.of());
	}

	@Test
	void perfectDifficultyMatchYieldsFullDifficultyAlignment() {
		// MOSTLY_EASY preferred mix is (60,30,10); an identical resort mix has zero L1 distance.
		ScoreBreakdown breakdown = scorer.score(
				new DifficultyMix(60, 30, 10),
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY));

		assertThat(breakdown.alignDiff()).isEqualTo(1.0, within(EPS));
	}

	@Test
	void difficultyAlignmentDropsWithNormalizedL1Distance() {
		// MOSTLY_EASY (60,30,10) vs a hard-skewed resort (10,30,60): L1 = 50+0+50 = 100; 1 - 100/200 = 0.5.
		ScoreBreakdown breakdown = scorer.score(
				new DifficultyMix(10, 30, 60),
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY));

		assertThat(breakdown.alignDiff()).isEqualTo(0.5, within(EPS));
	}

	@Test
	void experienceAlignmentComparesHardnessIndexToPerLevelTarget() {
		// Resort mix (10,30,60): hardness H = (0.5*30 + 60)/100 = 0.75; ADVANCED target 0.70 → 1 - 0.05 = 0.95.
		ScoreBreakdown breakdown = scorer.score(
				new DifficultyMix(10, 30, 60),
				profile(ExperienceLevel.ADVANCED, DifficultyBand.MOSTLY_HARD));

		assertThat(breakdown.alignExp()).isEqualTo(0.95, within(EPS));
	}

	@Test
	void combinedScoreIsTheWeightedBlendOfBothAxes() {
		// alignDiff = 1.0, alignExp = 0.95 → 0.5*1.0 + 0.5*0.95 = 0.975.
		ScoreBreakdown breakdown = scorer.score(
				new DifficultyMix(60, 30, 10),
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY));

		assertThat(breakdown.alignExp()).isEqualTo(0.95, within(EPS));
		assertThat(breakdown.score()).isEqualTo(0.975, within(EPS));
	}
}
