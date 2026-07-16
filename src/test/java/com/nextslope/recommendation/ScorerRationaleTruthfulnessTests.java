package com.nextslope.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nextslope.profile.DifficultyBand;
import com.nextslope.profile.ExperienceLevel;
import com.nextslope.profile.NoveltyPreference;
import com.nextslope.profile.ProfileSnapshot;
import com.nextslope.resort.Resort;

/**
 * Rationale-truthfulness contract proven against a {@link ScoreBreakdown} produced by the <em>real</em>
 * {@link WeightedDistanceScorer} (test-plan.md §2 Risk #1, §6.5). The sibling {@code RationaleBuilderTests}
 * hand-crafts every breakdown, which is tautological with respect to "the rationale corresponds to the
 * resort's real scoring reasons": it proves the builder's branch logic but never that the numbers it
 * branches on are the ones the scorer would actually emit. Here each test scores a real resort mix, feeds
 * that exact breakdown to the real {@link RationaleBuilder}, and asserts the emitted clause names an axis
 * the user set and matches an <em>independently</em> hand-computed alignment — closing the oracle gap one
 * seam deeper than the scorer's own value tests.
 *
 * <p>No {@link ScoreBreakdown} is ever constructed by hand in this class; every breakdown comes from
 * {@code scorer.score(...)}.
 */
class ScorerRationaleTruthfulnessTests {

	private final ScoringConfig config = ScoringConfig.defaults();
	private final WeightedDistanceScorer scorer = new WeightedDistanceScorer(config);
	private final RationaleBuilder rationaleBuilder = new RationaleBuilder(config);

	private static Resort resort(String name, String country, int easy, int med, int hard) {
		return Resort.builder()
				.id(1L)
				.name(name)
				.country(country)
				.active(true)
				.beginnerSlopes(easy)
				.intermediateSlopes(med)
				.difficultSlopes(hard)
				.totalSlopes(easy + med + hard)
				.build();
	}

	private static ProfileSnapshot profile(ExperienceLevel exp, DifficultyBand band, Set<String> regions) {
		return new ProfileSnapshot(exp, band, NoveltyPreference.REVISIT_OKAY, regions);
	}

	/** Runs the real scorer over the resort's real mix, then the real builder — never a hand-crafted breakdown. */
	private String realRationale(Resort resort, ProfileSnapshot profile) {
		ScoreBreakdown breakdown = scorer.score(resort.getDifficultyMix(), profile);
		return rationaleBuilder.build(resort, breakdown, profile);
	}

	@Test
	void difficultyWinsWhenItIsTheStrongestQualifyingAxis() {
		// Profile MOSTLY_EASY (60/30/10) / INTERMEDIATE (target 0.45), no region. Resort mix 60/30/10:
		// real alignDiff = 1 - 0/200 = 1.0; real alignExp = 1 - |0.25 - 0.45| = 0.80. Both clear the 0.6
		// threshold, but difficulty (1.0) > experience (0.80), so difficulty is the truthfully-named axis.
		ProfileSnapshot profile = profile(ExperienceLevel.INTERMEDIATE, DifficultyBand.MOSTLY_EASY, Set.of());
		String rationale = realRationale(resort("Solden", "Austria", 60, 30, 10), profile);

		assertThat(rationale).contains(DifficultyBand.MOSTLY_EASY.getLabel());
		assertThat(rationale).doesNotContain(ExperienceLevel.INTERMEDIATE.getLabel());
		assertThat(rationale).doesNotContain("selected region"); // no region set → the region axis is never named
	}

	@Test
	void experienceWinsWhenDifficultyFallsBelowThreshold() {
		// Profile MOSTLY_EASY / ADVANCED (target 0.70), no region. Resort mix 20/20/60: real alignDiff =
		// 1 - 100/200 = 0.50 (below the 0.6 threshold, disqualified); real alignExp = 1 - |0.70 - 0.70| =
		// 1.0. Experience is the only qualifying axis, and difficulty must NOT be named — 0.50 would lie.
		ProfileSnapshot profile = profile(ExperienceLevel.ADVANCED, DifficultyBand.MOSTLY_EASY, Set.of());
		String rationale = realRationale(resort("Verbier", "Switzerland", 20, 20, 60), profile);

		assertThat(rationale).contains(ExperienceLevel.ADVANCED.getLabel());
		assertThat(rationale).doesNotContain(DifficultyBand.MOSTLY_EASY.getLabel());
		assertThat(rationale).doesNotContain("selected region");
	}

	@Test
	void fallsBackGenericallyWhenBothAxesAreBelowThresholdAndNoRegionSet() {
		// Profile BALANCED (34/33/33) / INTERMEDIATE (target 0.45), no region. Resort mix 0/0/100: real
		// alignDiff = 1 - 134/200 = 0.33; real alignExp = 1 - |1.0 - 0.45| = 0.45. Both are below 0.6, so
		// no axis qualifies and the no-region generic fallback is used — naming neither soft axis (both lie).
		ProfileSnapshot profile = profile(ExperienceLevel.INTERMEDIATE, DifficultyBand.BALANCED, Set.of());
		String rationale = realRationale(resort("Bansko", "Bulgaria", 0, 0, 100), profile);

		assertThat(rationale).contains("one of the closest matches to your overall preferences");
		assertThat(rationale).doesNotContain(DifficultyBand.BALANCED.getLabel());
		assertThat(rationale).doesNotContain(ExperienceLevel.INTERMEDIATE.getLabel());
		assertThat(rationale).doesNotContain("selected region");
	}

	@Test
	void regionOutranksAPerfectDifficultyOnARealScoringTie() {
		// Profile MOSTLY_EASY / INTERMEDIATE, region = {Austria}; the resort is in Austria with mix
		// 60/30/10, so the REAL breakdown is alignDiff = 1.0 — a genuine, not hand-crafted, perfect
		// difficulty match. The satisfied region hard filter is pinned at alignment 1.0 and only a
		// STRICTLY greater soft alignment can override it, so region wins the 1.0/1.0 tie — proving the
		// strict-'>' priority rule holds against real scoring, not just a fabricated breakdown.
		ProfileSnapshot profile = profile(ExperienceLevel.INTERMEDIATE, DifficultyBand.MOSTLY_EASY, Set.of("Austria"));
		String rationale = realRationale(resort("Solden", "Austria", 60, 30, 10), profile);

		assertThat(rationale).contains("Austria");
		assertThat(rationale).doesNotContain(DifficultyBand.MOSTLY_EASY.getLabel());
	}
}
