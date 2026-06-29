package com.nextslope.recommendation;

import com.nextslope.profile.ExperienceLevel;

/**
 * The single home for every recommendation tunable. Approach A (weighted normalized distance) ships
 * with the defaults below; the separate refinement session (Phase 4 brief) owns the final values, so
 * no scoring magic number lives anywhere else in the engine.
 *
 * @param weightDiff weight of the difficulty-mix alignment in the combined score
 * @param weightExp weight of the experience alignment in the combined score
 * @param beginnerHardnessTarget hardness-index target for {@link ExperienceLevel#BEGINNER}
 * @param intermediateHardnessTarget hardness-index target for {@link ExperienceLevel#INTERMEDIATE}
 * @param advancedHardnessTarget hardness-index target for {@link ExperienceLevel#ADVANCED}
 * @param rationaleAlignmentThreshold minimum per-axis alignment for a rationale clause to be truthful
 */
public record ScoringConfig(
		double weightDiff,
		double weightExp,
		double beginnerHardnessTarget,
		double intermediateHardnessTarget,
		double advancedHardnessTarget,
		double rationaleAlignmentThreshold) {

	/** Defensible Approach A defaults; the refinement session retunes these in one place. */
	public static ScoringConfig defaults() {
		return new ScoringConfig(0.5, 0.5, 0.20, 0.45, 0.70, 0.6);
	}

	/** The hardness-index target the given experience level is scored against. */
	public double hardnessTarget(ExperienceLevel level) {
		return switch (level) {
			case BEGINNER -> beginnerHardnessTarget;
			case INTERMEDIATE -> intermediateHardnessTarget;
			case ADVANCED -> advancedHardnessTarget;
		};
	}
}
