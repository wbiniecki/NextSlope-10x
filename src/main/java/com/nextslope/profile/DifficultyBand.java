package com.nextslope.profile;

import com.nextslope.resort.DifficultyMix;

/**
 * Preset difficulty preference. The stored value is the band the user chose; each band maps to a
 * fixed easy/medium/hard triple (summing to 100) that S-05 scores against {@link DifficultyMix}.
 */
public enum DifficultyBand {

	MOSTLY_EASY("Mostly easy runs", new DifficultyMix(60, 30, 10)),
	BALANCED("Balanced mix", new DifficultyMix(34, 33, 33)),
	MOSTLY_HARD("Mostly hard runs", new DifficultyMix(10, 30, 60));

	private final String label;
	private final DifficultyMix mix;

	DifficultyBand(String label, DifficultyMix mix) {
		this.label = label;
		this.mix = mix;
	}

	public String getLabel() {
		return label;
	}

	public DifficultyMix toMix() {
		return mix;
	}
}
