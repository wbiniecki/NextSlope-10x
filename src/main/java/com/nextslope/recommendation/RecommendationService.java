package com.nextslope.recommendation;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextslope.profile.NoveltyPreference;
import com.nextslope.profile.PreferenceProfileService;
import com.nextslope.profile.ProfileSnapshot;
import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;
import com.nextslope.visited.VisitedResortService;

import lombok.RequiredArgsConstructor;

/**
 * The single recommendation entry point. Loads the user's profile snapshot, builds the active
 * candidate pool, applies the region and novelty hard filters, scores the survivors with the pluggable
 * {@link Scorer}, orders them deterministically, and returns either the top three ranked cards or an
 * explicit sparse / no-profile result — owner-scoped by {@code userId}, never by an addressable id.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

	private final PreferenceProfileService preferenceProfileService;
	private final ResortRepository resortRepository;
	private final VisitedResortService visitedResortService;
	private final Scorer scorer;
	private final RationaleBuilder rationaleBuilder;

	/** A scored candidate; carried only long enough to rank and project into a {@link ResortCard}. */
	private record Scored(Resort resort, ScoreBreakdown breakdown) {
	}

	// Deterministic total order: score desc, then a content-derived tie-break (country, name, id) —
	// never the iteration order of any HashSet/HashMap.
	private static final Comparator<Scored> RANKING = Comparator
			.comparingDouble((Scored s) -> s.breakdown().score()).reversed()
			.thenComparing(s -> s.resort().getCountry())
			.thenComparing(s -> s.resort().getName())
			.thenComparing(s -> s.resort().getId());

	@Transactional(readOnly = true)
	public RecommendationResult recommend(Long userId) {
		return preferenceProfileService.snapshotForUser(userId)
				.map(profile -> recommendFor(userId, profile))
				.orElseGet(() -> RecommendationResult.noProfile(
						"Set up your preference profile to get three tailored resort picks."));
	}

	private RecommendationResult recommendFor(Long userId, ProfileSnapshot profile) {
		Set<Long> visited = profile.noveltyPreference() == NoveltyPreference.NEW_ONLY
				? visitedResortService.visitedResortIds(userId)
				: Set.of();

		List<Resort> survivors = resortRepository.findByActiveTrueOrderByCountryAscNameAsc().stream()
				.filter(resort -> !profile.hasRegionFilter()
						|| profile.regionCountries().contains(resort.getCountry()))
				.filter(resort -> !visited.contains(resort.getId()))
				.toList();

		if (survivors.size() < 3) {
			return RecommendationResult.sparse(sparseExplanation(survivors.size(), profile));
		}

		List<ResortCard> cards = survivors.stream()
				.map(resort -> new Scored(resort, scorer.score(resort.getDifficultyMix(), profile)))
				.sorted(RANKING)
				.limit(3)
				.map(scored -> toCard(scored, profile))
				.toList();

		return RecommendationResult.recommendations(cards);
	}

	private ResortCard toCard(Scored scored, ProfileSnapshot profile) {
		Resort resort = scored.resort();
		return new ResortCard(
				resort.getId(),
				resort.getName(),
				resort.getCountry(),
				resort.getHighestPoint(),
				resort.getTotalSlopes(),
				resort.getTotalLifts(),
				resort.getDifficultyMix(),
				rationaleBuilder.build(resort, scored.breakdown(), profile));
	}

	private static String sparseExplanation(int survivors, ProfileSnapshot profile) {
		String matches = survivors == 0
				? "We couldn't find any resorts matching your filters"
				: "We found only " + survivors + (survivors == 1 ? " resort" : " resorts") + " matching your filters";
		String suggestion = profile.noveltyPreference() == NoveltyPreference.NEW_ONLY
				? " Try widening your region or allowing revisits to see three picks."
				: " Try widening your selected region to see three picks.";
		return matches + ", so we can't show three recommendations yet." + suggestion;
	}
}
