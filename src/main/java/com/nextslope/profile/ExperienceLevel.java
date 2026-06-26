package com.nextslope.profile;

/**
 * Ordered self-rated skiing experience. Read by S-05 to soft-score resort fit.
 */
public enum ExperienceLevel {

	BEGINNER("Beginner"),
	INTERMEDIATE("Intermediate"),
	ADVANCED("Advanced");

	private final String label;

	ExperienceLevel(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
