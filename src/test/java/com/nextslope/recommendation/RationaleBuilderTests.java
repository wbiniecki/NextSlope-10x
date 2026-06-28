package com.nextslope.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nextslope.profile.DifficultyBand;
import com.nextslope.profile.ExperienceLevel;
import com.nextslope.profile.NoveltyPreference;
import com.nextslope.profile.ProfileSnapshot;
import com.nextslope.resort.Resort;

class RationaleBuilderTests {

	private final RationaleBuilder builder = new RationaleBuilder(ScoringConfig.defaults());

	private static Resort resort(String name, String country) {
		return Resort.builder().id(1L).name(name).country(country).active(true).build();
	}

	private static ProfileSnapshot profile(ExperienceLevel exp, DifficultyBand band, Set<String> regions) {
		return new ProfileSnapshot(exp, band, NoveltyPreference.REVISIT_OKAY, regions);
	}

	@Test
	void namesTheSelectedRegionWhenTheUserConstrainedRegion() {
		// Region is a satisfied hard filter (alignment 1.0); it outranks weaker soft axes.
		String rationale = builder.build(
				resort("Chamonix", "France"),
				new ScoreBreakdown(0.4, 0.4, 0.4),
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY, Set.of("France")));

		assertThat(rationale).contains("France");
		assertThat(rationale).containsIgnoringCase("region");
	}

	@Test
	void picksTheStrongestQualifyingAxisAmongExperienceAndDifficulty() {
		// Any-region profile; difficulty (0.95) beats experience (0.7), both above the 0.6 threshold.
		String rationale = builder.build(
				resort("Kitzbuhel", "Austria"),
				new ScoreBreakdown(0.95, 0.7, 0.825),
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY, Set.of()));

		assertThat(rationale).contains(DifficultyBand.MOSTLY_EASY.getLabel());
		assertThat(rationale).doesNotContain(ExperienceLevel.BEGINNER.getLabel());
	}

	@Test
	void neverNamesDifficultyWhenItsAlignmentIsBelowThreshold() {
		// Difficulty alignment 0.55 < 0.6 must not be claimed; experience 0.9 qualifies and is named.
		String rationale = builder.build(
				resort("Verbier", "Switzerland"),
				new ScoreBreakdown(0.55, 0.9, 0.725),
				profile(ExperienceLevel.ADVANCED, DifficultyBand.MOSTLY_HARD, Set.of()));

		assertThat(rationale).doesNotContain(DifficultyBand.MOSTLY_HARD.getLabel());
		assertThat(rationale).contains(ExperienceLevel.ADVANCED.getLabel());
	}

	@Test
	void fallsBackTruthfullyWhenNoAxisQualifies() {
		// Both soft axes below threshold and no region filter → generic fallback, no axis claim.
		String rationale = builder.build(
				resort("Bansko", "Bulgaria"),
				new ScoreBreakdown(0.4, 0.4, 0.4),
				profile(ExperienceLevel.INTERMEDIATE, DifficultyBand.BALANCED, Set.of()));

		assertThat(rationale).doesNotContain(DifficultyBand.BALANCED.getLabel());
		assertThat(rationale).doesNotContain(ExperienceLevel.INTERMEDIATE.getLabel());
		assertThat(rationale).isNotBlank();
	}
}
