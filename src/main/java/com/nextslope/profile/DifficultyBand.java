package com.nextslope.profile;

import com.nextslope.resort.DifficultyMix;

/**
 * Preset difficulty preference. The stored value is the band the user chose; each band maps to a
 * fixed easy/medium/hard triple (summing to 100) that S-05 scores against {@link DifficultyMix}.
 */
public enum DifficultyBand {

	MOSTLY_EASY(new DifficultyMix(60, 30, 10)),
	BALANCED(new DifficultyMix(34, 33, 33)),
	MOSTLY_HARD(new DifficultyMix(10, 30, 60));

	private final DifficultyMix mix;

	DifficultyBand(DifficultyMix mix) {
		this.mix = mix;
	}

	public DifficultyMix toMix() {
		return mix;
	}
}
