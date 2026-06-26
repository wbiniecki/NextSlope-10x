package com.nextslope.profile;

/**
 * Whether the user wants only resorts they have never visited, or is happy to revisit. S-05 uses
 * {@code NEW_ONLY} as a hard filter against the user's visited list.
 */
public enum NoveltyPreference {
	NEW_ONLY,
	REVISIT_OKAY
}
