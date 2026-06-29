package com.nextslope.recommendation;

import com.nextslope.profile.ProfileSnapshot;
import com.nextslope.resort.DifficultyMix;

/**
 * Soft-alignment scoring SPI. Implementations compute per-axis alignments and a combined score for a
 * surviving candidate; the interface lets the refinement session swap or retune the algorithm without
 * touching the {@code RecommendationService} call site.
 */
public interface Scorer {

	/** Score a candidate resort's difficulty mix against the user's profile axes. */
	ScoreBreakdown score(DifficultyMix resortMix, ProfileSnapshot profile);
}
