package com.nextslope.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nextslope.resort.DifficultyMix;

class DifficultyBandTests {

	@Test
	void mostlyEasyMapsToCanonicalTriple() {
		assertThat(DifficultyBand.MOSTLY_EASY.toMix()).isEqualTo(new DifficultyMix(60, 30, 10));
	}

	@Test
	void balancedMapsToCanonicalTriple() {
		assertThat(DifficultyBand.BALANCED.toMix()).isEqualTo(new DifficultyMix(34, 33, 33));
	}

	@Test
	void mostlyHardMapsToCanonicalTriple() {
		assertThat(DifficultyBand.MOSTLY_HARD.toMix()).isEqualTo(new DifficultyMix(10, 30, 60));
	}

	@Test
	void everyBandMixSumsTo100() {
		for (DifficultyBand band : DifficultyBand.values()) {
			DifficultyMix mix = band.toMix();
			assertThat(mix.easy() + mix.medium() + mix.hard())
					.as("band %s sums to 100", band)
					.isEqualTo(100);
		}
	}
}
