package com.nextslope.resort;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResortDifficultyMixTests {

	@Test
	void derivesPercentagesThatSumTo100() {
		Resort resort = Resort.builder()
				.beginnerSlopes(75)
				.intermediateSlopes(220)
				.difficultSlopes(27)
				.build();

		DifficultyMix mix = resort.getDifficultyMix();

		assertThat(mix.easy() + mix.medium() + mix.hard()).isEqualTo(100);
		assertThat(mix.easy()).isEqualTo(23);
		assertThat(mix.medium()).isEqualTo(68);
		assertThat(mix.hard()).isEqualTo(9);
	}

	@Test
	void distributesRoundingRemainderSoEqualThirdsStillSumTo100() {
		Resort resort = Resort.builder()
				.beginnerSlopes(1)
				.intermediateSlopes(1)
				.difficultSlopes(1)
				.build();

		DifficultyMix mix = resort.getDifficultyMix();

		assertThat(mix.easy() + mix.medium() + mix.hard()).isEqualTo(100);
		assertThat(mix.easy()).isEqualTo(34);
		assertThat(mix.medium()).isEqualTo(33);
		assertThat(mix.hard()).isEqualTo(33);
	}

	@Test
	void guardsAgainstZeroDenominator() {
		Resort resort = Resort.builder()
				.beginnerSlopes(0)
				.intermediateSlopes(0)
				.difficultSlopes(0)
				.build();

		DifficultyMix mix = resort.getDifficultyMix();

		assertThat(mix.easy()).isZero();
		assertThat(mix.medium()).isZero();
		assertThat(mix.hard()).isZero();
	}

	@Test
	void treatsNullSlopeCountsAsZero() {
		Resort resort = Resort.builder().build();

		DifficultyMix mix = resort.getDifficultyMix();

		assertThat(mix.easy()).isZero();
		assertThat(mix.medium()).isZero();
		assertThat(mix.hard()).isZero();
	}
}
