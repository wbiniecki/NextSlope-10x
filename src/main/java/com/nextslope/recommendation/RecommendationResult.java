package com.nextslope.recommendation;

import java.util.List;

/**
 * The discriminated outcome of a recommendation request: exactly three ranked cards, an explicit
 * sparse explanation when fewer than three candidates survive the hard filters, or a prompt to set up
 * a profile when none exists. Never a padded or silently-short card list.
 *
 * @param kind which of the three outcomes this is
 * @param cards the ranked cards (empty unless {@link Kind#RECOMMENDATIONS})
 * @param explanation the user-facing message for the sparse / no-profile states (null otherwise)
 */
public record RecommendationResult(Kind kind, List<ResortCard> cards, String explanation) {

	public enum Kind {
		RECOMMENDATIONS,
		SPARSE,
		NO_PROFILE
	}

	public static RecommendationResult recommendations(List<ResortCard> cards) {
		return new RecommendationResult(Kind.RECOMMENDATIONS, List.copyOf(cards), null);
	}

	public static RecommendationResult sparse(String explanation) {
		return new RecommendationResult(Kind.SPARSE, List.of(), explanation);
	}

	public static RecommendationResult noProfile(String explanation) {
		return new RecommendationResult(Kind.NO_PROFILE, List.of(), explanation);
	}

	public boolean isRecommendations() {
		return kind == Kind.RECOMMENDATIONS;
	}

	public boolean isSparse() {
		return kind == Kind.SPARSE;
	}

	public boolean isNoProfile() {
		return kind == Kind.NO_PROFILE;
	}
}
