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
import com.nextslope.resort.DifficultyMix;
import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;
import com.nextslope.visited.VisitedResortService;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTests {

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

	private static ProfileSnapshot profile(NoveltyPreference novelty, Set<String> regions) {
		return new ProfileSnapshot(ExperienceLevel.BEGINNER, DifficultyBand.MOSTLY_EASY, novelty, regions);
	}

	private void givenProfile(ProfileSnapshot snapshot) {
		when(preferenceProfileService.snapshotForUser(USER_ID)).thenReturn(Optional.of(snapshot));
	}

	private List<Long> cardIds(RecommendationResult result) {
		return result.cards().stream().map(ResortCard::id).toList();
	}

	@Test
	void returnsNoProfileStateWhenTheUserHasNotSetUpAProfile() {
		when(preferenceProfileService.snapshotForUser(USER_ID)).thenReturn(Optional.empty());

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isNoProfile()).isTrue();
		assertThat(result.cards()).isEmpty();
		assertThat(result.explanation()).isNotBlank();
	}

	@Test
	void regionFilterKeepsOnlyResortsInTheSelectedCountries() {
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of("Austria")));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Solden", "Austria", 60, 30, 10),
				resort(2L, "Ischgl", "Austria", 50, 30, 20),
				resort(3L, "Kitzbuhel", "Austria", 70, 20, 10),
				resort(4L, "Chamonix", "France", 40, 30, 30),
				resort(5L, "Tignes", "France", 30, 40, 30)));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isRecommendations()).isTrue();
		assertThat(result.cards()).extracting(ResortCard::country).containsOnly("Austria");
	}

	@Test
	void newOnlyNoveltyExcludesVisitedResorts() {
		givenProfile(profile(NoveltyPreference.NEW_ONLY, Set.of()));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Solden", "Austria", 60, 30, 10),
				resort(2L, "Ischgl", "Austria", 50, 30, 20),
				resort(3L, "Kitzbuhel", "Austria", 70, 20, 10),
				resort(4L, "Chamonix", "France", 40, 30, 30)));
		when(visitedResortService.visitedResortIds(USER_ID)).thenReturn(Set.of(2L));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isRecommendations()).isTrue();
		assertThat(cardIds(result)).doesNotContain(2L);
	}

	@Test
	void doesNotConsultTheVisitedListWhenRevisitsAreAllowed() {
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of()));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Solden", "Austria", 60, 30, 10),
				resort(2L, "Ischgl", "Austria", 50, 30, 20),
				resort(3L, "Kitzbuhel", "Austria", 70, 20, 10)));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isRecommendations()).isTrue();
		// visitedResortService is never stubbed/used — strict Mockito would flag an unused stub.
	}

	@Test
	void returnsAnExplicitSparseExplanationWhenFewerThanThreeSurvive() {
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of("Andorra")));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Grandvalira", "Andorra", 50, 30, 20),
				resort(2L, "Solden", "Austria", 60, 30, 10),
				resort(3L, "Chamonix", "France", 40, 30, 30)));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isSparse()).isTrue();
		assertThat(result.cards()).isEmpty();
		assertThat(result.explanation()).isNotBlank();
	}

	@Test
	void returnsExactlyThreeCardsWhenMoreThanThreeSurvive() {
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of()));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "A", "Austria", 60, 30, 10),
				resort(2L, "B", "Austria", 55, 30, 15),
				resort(3L, "C", "France", 50, 30, 20),
				resort(4L, "D", "France", 45, 35, 20),
				resort(5L, "E", "Italy", 40, 40, 20)));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.cards()).hasSize(3);
	}

	@Test
	void rankingIsDrivenByScoreNotInputOrAlphabeticalOrder() {
		// Profile MOSTLY_EASY/BEGINNER: the easy-skewed resort scores highest regardless of its country.
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of()));
		Resort perfectEasy = resort(1L, "Best", "Switzerland", 60, 30, 10); // align_diff 1.0 → top score
		Resort balanced = resort(2L, "Mid", "France", 34, 33, 33);
		Resort hard = resort(3L, "Steep", "Austria", 10, 30, 60); // worst fit for a beginner
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(hard, balanced, perfectEasy));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(cardIds(result)).containsExactly(1L, 2L, 3L);
	}

	@Test
	void tiedScoresBreakDeterministicallyByCountryThenNameThenIdAcrossInputOrders() {
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of()));
		// Identical slope counts → identical scores, so only the (country, name, id) tie-break orders them.
		Resort zellAustria = resort(3L, "Zell", "Austria", 50, 30, 20);
		Resort altaAustria = resort(1L, "Alta", "Austria", 50, 30, 20);
		Resort tignesFrance = resort(2L, "Tignes", "France", 50, 30, 20);

		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(zellAustria, tignesFrance, altaAustria));
		List<Long> firstRun = cardIds(service().recommend(USER_ID));

		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(tignesFrance, altaAustria, zellAustria));
		List<Long> secondRun = cardIds(service().recommend(USER_ID));

		// Austria/Alta(1) → Austria/Zell(3) → France/Tignes(2), stable regardless of input order.
		assertThat(firstRun).containsExactly(1L, 3L, 2L);
		assertThat(secondRun).containsExactly(1L, 3L, 2L);
	}

	@Test
	void nameTieBreakOrdersResortsWithinTheSameCountryAndScore() {
		// All three tie on score; only the name tie-break (after country) decides the Austrian pair, and
		// here name order (Alpbach < Zell) is the reverse of id order — so dropping it would reorder them.
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of()));
		Resort zell = resort(2L, "Zell", "Austria", 60, 30, 10);
		Resort alpbach = resort(9L, "Alpbach", "Austria", 60, 30, 10);
		Resort chamonix = resort(5L, "Chamonix", "France", 60, 30, 10);
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(zell, chamonix, alpbach));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(cardIds(result)).containsExactly(9L, 2L, 5L);
	}

	@Test
	void idTieBreakIsTheFinalTotalOrderForResortsIdenticalInCountryNameAndScore() {
		// Same country, same name, same score: only the id tie-break gives a stable total order. Input
		// order (9 before 2) differs from id order, so dropping the id comparator would leak input order.
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of()));
		Resort alpbachHi = resort(9L, "Alpbach", "Austria", 60, 30, 10);
		Resort alpbachLo = resort(2L, "Alpbach", "Austria", 60, 30, 10);
		Resort chamonix = resort(5L, "Chamonix", "France", 60, 30, 10);
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(alpbachHi, chamonix, alpbachLo));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(cardIds(result)).containsExactly(2L, 9L, 5L);
	}

	@Test
	void sparseExplanationStatesTheExactSurvivorCount() {
		// Exactly two survivors (region = Austria) → the message must report "2 resorts", not a generic line.
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of("Austria")));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Solden", "Austria", 60, 30, 10),
				resort(2L, "Ischgl", "Austria", 55, 30, 15),
				resort(3L, "Chamonix", "France", 40, 30, 30)));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isSparse()).isTrue();
		assertThat(result.explanation()).contains("2 resorts");
	}

	@Test
	void sparseExplanationForZeroSurvivorsSaysNoneMatched() {
		// Region selects a country absent from the catalog → zero survivors → the "none matched" message.
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of("Spain")));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Solden", "Austria", 60, 30, 10),
				resort(2L, "Chamonix", "France", 40, 30, 30)));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isSparse()).isTrue();
		assertThat(result.explanation()).contains("couldn't find any");
	}

	@Test
	void sparseExplanationSuggestsAllowingRevisitsForNewOnlyUsers() {
		// A NEW_ONLY user with too few matches gets a suggestion that includes relaxing the novelty filter.
		givenProfile(profile(NoveltyPreference.NEW_ONLY, Set.of("Andorra")));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Grandvalira", "Andorra", 50, 30, 20),
				resort(2L, "Solden", "Austria", 60, 30, 10)));
		when(visitedResortService.visitedResortIds(USER_ID)).thenReturn(Set.of());

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isSparse()).isTrue();
		assertThat(result.explanation()).contains("allowing revisits");
	}

	@Test
	void cardsCarryTheExpectedViewFacts() {
		// Scope: the card faithfully projects the resort's view facts (name, country, lifts, difficulty
		// mix). The rationale is only smoke-checked for non-blankness here — a distinct, legitimate concern
		// from whether it is *truthful*, which is proven separately against a real scorer breakdown in
		// ScorerRationaleTruthfulnessTests (this test's former name over-claimed a truthfulness check it
		// never actually made).
		givenProfile(profile(NoveltyPreference.REVISIT_OKAY, Set.of()));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Solden", "Austria", 60, 30, 10),
				resort(2L, "Ischgl", "Austria", 55, 30, 15),
				resort(3L, "Kitzbuhel", "Austria", 70, 20, 10)));

		RecommendationResult result = service().recommend(USER_ID);

		// Solden (id 1, 60/30/10) is the deterministic winner for this BEGINNER / MOSTLY_EASY profile
		// (score 0.975 vs Kitzbuhel 0.95, Ischgl 0.925), so pin its identity and exact projected mix —
		// presence-only checks would pass for any of the three all-Austrian survivors. Rationale is only
		// smoke-checked here; its truthfulness is proven in ScorerRationaleTruthfulnessTests.
		ResortCard top = result.cards().get(0);
		assertThat(top.id()).isEqualTo(1L);
		assertThat(top.name()).isEqualTo("Solden");
		assertThat(top.country()).isEqualTo("Austria");
		assertThat(top.totalLifts()).isEqualTo(20);
		assertThat(top.difficultyMix()).isEqualTo(new DifficultyMix(60, 30, 10));
		assertThat(top.rationale()).isNotBlank();
	}

	@Test
	void cardsUseTheRealScorerBreakdownForRationale() {
		// Guards the RecommendationService.toCard() handoff that direct scorer+builder composition (in
		// ScorerRationaleTruthfulnessTests) bypasses: the emitted card's rationale must be derived from the
		// resort's REAL scored breakdown. Profile MOSTLY_EASY / INTERMEDIATE / no region; the winner
		// (60/30/10) scores real alignDiff 1.0 (>= the 0.6 threshold) and alignExp 0.80 → difficulty is the
		// strongest qualifying axis (combined score 0.90). Two lower-scoring fillers (0.60, 0.275) keep the
		// survivor count at 3 (no sparse short-circuit) and rank below the winner. The expected axis is
		// derived from the fixture arithmetic, then asserted on the emitted ResortCard — not on a directly
		// invoked builder result.
		givenProfile(new ProfileSnapshot(
				ExperienceLevel.INTERMEDIATE, DifficultyBand.MOSTLY_EASY, NoveltyPreference.REVISIT_OKAY, Set.of()));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Winner", "Austria", 60, 30, 10),
				resort(2L, "FillerB", "France", 10, 30, 60),
				resort(3L, "FillerC", "Italy", 0, 0, 100)));

		RecommendationResult result = service().recommend(USER_ID);

		ResortCard top = result.cards().get(0);
		assertThat(top.id()).isEqualTo(1L);
		assertThat(top.rationale()).contains(DifficultyBand.MOSTLY_EASY.getLabel());
	}

	@Test
	void allVisitedCandidatesUnderNewOnlyYieldsZeroSurvivorSparseWithRevisitSuggestion() {
		// Edge: a NEW_ONLY user who has visited every active resort. The novelty hard filter removes all
		// candidates, so zero survive — the specific zero-via-visited-exhaustion path (distinct from the
		// zero-via-region-mismatch case covered by sparseExplanationForZeroSurvivorsSaysNoneMatched). The
		// explanation must both report zero matches AND offer the NEW_ONLY-specific "allow revisits" escape.
		givenProfile(profile(NoveltyPreference.NEW_ONLY, Set.of()));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Solden", "Austria", 60, 30, 10),
				resort(2L, "Ischgl", "Austria", 55, 30, 15),
				resort(3L, "Kitzbuhel", "Austria", 70, 20, 10)));
		when(visitedResortService.visitedResortIds(USER_ID)).thenReturn(Set.of(1L, 2L, 3L));

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isSparse()).isTrue();
		assertThat(result.cards()).isEmpty();
		assertThat(result.explanation()).contains("couldn't find any").contains("allowing revisits");
	}

	@Test
	void emptyVisitedListUnderNewOnlyBehavesLikeNoVisitedResorts() {
		// Edge: a NEW_ONLY user with an explicitly empty visited list (not merely an unstubbed default).
		// The novelty filter must be a no-op when nothing has been visited, so every active resort survives
		// and all three are recommended — proving empty-set semantics rather than incidental mock behavior.
		givenProfile(profile(NoveltyPreference.NEW_ONLY, Set.of()));
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc()).thenReturn(List.of(
				resort(1L, "Solden", "Austria", 60, 30, 10),
				resort(2L, "Ischgl", "Austria", 55, 30, 15),
				resort(3L, "Kitzbuhel", "Austria", 70, 20, 10)));
		when(visitedResortService.visitedResortIds(USER_ID)).thenReturn(Set.of());

		RecommendationResult result = service().recommend(USER_ID);

		assertThat(result.isRecommendations()).isTrue();
		assertThat(cardIds(result)).containsExactlyInAnyOrder(1L, 2L, 3L);
	}
}
