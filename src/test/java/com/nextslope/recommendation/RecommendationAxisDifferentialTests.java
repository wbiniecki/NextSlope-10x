package com.nextslope.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nextslope.profile.DifficultyBand;
import com.nextslope.profile.ExperienceLevel;
import com.nextslope.profile.NoveltyPreference;
import com.nextslope.profile.PreferenceProfileService;
import com.nextslope.profile.ProfileSnapshot;
import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;
import com.nextslope.visited.VisitedResortService;

/**
 * Single-axis differential proof for all four preference axes (test-plan.md §2 Risk #2, §6.5). Each
 * test calls {@link RecommendationService#recommend(Long)} twice with fixtures that are identical
 * except the one axis under test, and asserts the observable that axis's pipeline stage can actually
 * produce: a <em>candidate-set</em> change for the two hard-filter axes (region, novelty), an
 * <em>ordering</em> change for the two soft-scored axes (difficulty band, experience level). The real
 * {@link WeightedDistanceScorer} and {@link RationaleBuilder} run (never mocked); only the repository
 * boundaries are stubbed. Per the §6.5 anti-pattern warning, assertions are set/rank deltas — never a
 * score number copied from the scorer.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationAxisDifferentialTests {

	private static final Long USER_ID = 1L;

	@Mock
	private PreferenceProfileService preferenceProfileService;

	@Mock
	private ResortRepository resortRepository;

	@Mock
	private VisitedResortService visitedResortService;

	private RecommendationService service;

	private RecommendationService service() {
		if (service == null) {
			ScoringConfig config = ScoringConfig.defaults();
			service = new RecommendationService(
					preferenceProfileService,
					resortRepository,
					visitedResortService,
					new WeightedDistanceScorer(config),
					new RationaleBuilder(config));
		}
		return service;
	}

	private static Resort resort(long id, String name, String country, int easy, int med, int hard) {
		return Resort.builder()
				.id(id)
				.name(name)
				.country(country)
				.active(true)
				.beginnerSlopes(easy)
				.intermediateSlopes(med)
				.difficultSlopes(hard)
				.totalSlopes(easy + med + hard)
				.totalLifts(20)
				.highestPoint(2500)
				.build();
	}

	private static ProfileSnapshot profile(
			ExperienceLevel experience, DifficultyBand band, NoveltyPreference novelty, Set<String> regions) {
		return new ProfileSnapshot(experience, band, novelty, regions);
	}

	private void givenCatalog(List<Resort> catalog) {
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(catalog);
	}

	private RecommendationResult recommendWith(ProfileSnapshot snapshot) {
		when(preferenceProfileService.snapshotForUser(USER_ID)).thenReturn(Optional.of(snapshot));
		return service().recommend(USER_ID);
	}

	private static List<Long> cardIds(RecommendationResult result) {
		return result.cards().stream().map(ResortCard::id).toList();
	}

	@Test
	void flippingOnlyTheRegionFilterChangesWhichResortsSurvive() {
		// Region is a HARD filter (no scoring), so the observable is a candidate-SET change. Everything
		// but regionCountries is held fixed (MOSTLY_EASY / BEGINNER / REVISIT_OKAY). The three Austrian
		// resorts each score 0.475; the lone French resort (Chamonix, 60/30/10) scores 0.975. Both runs
		// keep >=3 survivors so neither short-circuits to sparse — isolating the filter, not the count.
		givenCatalog(List.of(
				resort(1L, "Solden", "Austria", 10, 30, 60),
				resort(2L, "Ischgl", "Austria", 10, 30, 60),
				resort(3L, "Kitzbuhel", "Austria", 10, 30, 60),
				resort(4L, "Chamonix", "France", 60, 30, 10)));

		RecommendationResult austriaOnly = recommendWith(
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY, NoveltyPreference.REVISIT_OKAY, Set.of("Austria")));
		RecommendationResult anyRegion = recommendWith(
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY, NoveltyPreference.REVISIT_OKAY, Set.of()));

		assertThat(austriaOnly.isRecommendations()).isTrue();
		assertThat(anyRegion.isRecommendations()).isTrue();
		// The France-only resort is filtered out under {Austria} but is the top pick once the filter drops.
		assertThat(cardIds(austriaOnly)).doesNotContain(4L);
		assertThat(cardIds(anyRegion)).contains(4L);
	}

	@Test
	void flippingOnlyTheNoveltyPreferenceChangesWhichResortsSurvive() {
		// Novelty is a HARD filter (no scoring) → candidate-SET change. Region/band/experience held fixed
		// (no region / MOSTLY_EASY / BEGINNER); only NEW_ONLY vs REVISIT_OKAY differs, with the same
		// visited stub. Resort 1 is a unique perfect match (60/30/10, score 0.975) and is the visited one;
		// NEW_ONLY must drop it (leaving 3 survivors → recommendations), REVISIT_OKAY must keep it (top pick).
		givenCatalog(List.of(
				resort(1L, "Chamonix", "France", 60, 30, 10),
				resort(2L, "Solden", "Austria", 10, 30, 60),
				resort(3L, "Ischgl", "Austria", 10, 30, 60),
				resort(4L, "Tignes", "France", 10, 30, 60)));
		when(visitedResortService.visitedResortIds(USER_ID)).thenReturn(Set.of(1L));

		RecommendationResult newOnly = recommendWith(
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY, NoveltyPreference.NEW_ONLY, Set.of()));
		RecommendationResult revisitOkay = recommendWith(
				profile(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY, NoveltyPreference.REVISIT_OKAY, Set.of()));

		// Both must produce cards, else "doesNotContain" would pass vacuously on an empty sparse result.
		assertThat(newOnly.isRecommendations()).isTrue();
		assertThat(revisitOkay.isRecommendations()).isTrue();
		assertThat(cardIds(newOnly)).doesNotContain(1L);
		assertThat(cardIds(revisitOkay)).contains(1L);
	}

	@Test
	void flippingOnlyTheDifficultyBandReordersTheTopPick() {
		// Difficulty band is SOFT-scored (alignDiff), so with the resort set held fixed the observable is
		// an ORDERING change. Fixed profile: INTERMEDIATE / no region / REVISIT_OKAY; only the band flips.
		// alignExp is constant across both runs (depends only on the resort mix + INTERMEDIATE target 0.45).
		//   id 1 (60/30/10): score 0.90 @ MOSTLY_EASY, 0.65 @ MOSTLY_HARD
		//   id 2 (34/33/33): score 0.8475 @ MOSTLY_EASY, 0.8425 @ MOSTLY_HARD
		//   id 3 (10/30/60): score 0.60 @ MOSTLY_EASY, 0.85 @ MOSTLY_HARD
		// → top pick is resort 1 under MOSTLY_EASY and resort 3 under MOSTLY_HARD.
		givenCatalog(List.of(
				resort(1L, "Easy", "Austria", 60, 30, 10),
				resort(2L, "Balanced", "France", 34, 33, 33),
				resort(3L, "Hard", "Italy", 10, 30, 60)));

		RecommendationResult mostlyEasy = recommendWith(
				profile(ExperienceLevel.INTERMEDIATE, DifficultyBand.MOSTLY_EASY, NoveltyPreference.REVISIT_OKAY, Set.of()));
		RecommendationResult mostlyHard = recommendWith(
				profile(ExperienceLevel.INTERMEDIATE, DifficultyBand.MOSTLY_HARD, NoveltyPreference.REVISIT_OKAY, Set.of()));

		assertThat(cardIds(mostlyEasy).get(0)).isEqualTo(1L);
		assertThat(cardIds(mostlyHard).get(0)).isEqualTo(3L);
	}

	@Test
	void flippingOnlyTheExperienceLevelReordersTheTopPick() {
		// Experience level is SOFT-scored (alignExp), so with the resort set held fixed the observable is
		// an ORDERING change. Fixed profile: BALANCED / no region / REVISIT_OKAY; only the level flips.
		// alignDiff is constant across both runs (depends only on the resort mix vs the BALANCED mix).
		//   id 1 (70/20/10, hardness 0.20): score 0.82 @ BEGINNER, 0.57 @ ADVANCED
		//   id 2 (20/20/60, hardness 0.70): score 0.615 @ BEGINNER, 0.865 @ ADVANCED
		//   id 3 (0/0/100, hardness 1.00): score 0.265 @ BEGINNER, 0.515 @ ADVANCED (never leads; keeps count at 3)
		// → top pick is resort 1 under BEGINNER and resort 2 under ADVANCED.
		givenCatalog(List.of(
				resort(1L, "Soft", "Austria", 70, 20, 10),
				resort(2L, "Hard", "France", 20, 20, 60),
				resort(3L, "Filler", "Italy", 0, 0, 100)));

		RecommendationResult beginner = recommendWith(
				profile(ExperienceLevel.BEGINNER, DifficultyBand.BALANCED, NoveltyPreference.REVISIT_OKAY, Set.of()));
		RecommendationResult advanced = recommendWith(
				profile(ExperienceLevel.ADVANCED, DifficultyBand.BALANCED, NoveltyPreference.REVISIT_OKAY, Set.of()));

		assertThat(cardIds(beginner).get(0)).isEqualTo(1L);
		assertThat(cardIds(advanced).get(0)).isEqualTo(2L);
	}
}
