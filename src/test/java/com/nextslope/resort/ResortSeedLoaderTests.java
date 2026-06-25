package com.nextslope.resort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class ResortSeedLoaderTests {

	@Autowired
	private ResortRepository resortRepository;

	@Test
	void seedsExactlyFortyResortsIntoAnEmptyDatabase() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository);

		loader.run(null);

		assertThat(resortRepository.count()).isEqualTo(40);
	}

	@Test
	void secondRunAgainstPopulatedTableInsertsNothing() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository);
		loader.run(null);

		assertThatCode(() -> loader.run(null)).doesNotThrowAnyException();

		assertThat(resortRepository.count()).isEqualTo(40);
	}

	@Test
	void preservesQuotedCommaSeasonAndUtf8Names() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository);
		loader.run(null);

		Resort tignes = findByNamePrefix("Tignes");
		assertThat(tignes.getSeason()).isEqualTo("November - May, June - August");

		boolean hasSolden = resortRepository.findByActiveTrueOrderByCountryAscNameAsc().stream()
				.anyMatch(r -> "Sölden".equals(r.getName()));
		assertThat(hasSolden).isTrue();
	}

	@Test
	void mapsYesNoColumnsToBooleans() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository);
		loader.run(null);

		Resort zermatt = findByNamePrefix("Zermatt");
		assertThat(zermatt.getChildFriendly()).isTrue();
		assertThat(zermatt.getSummerSkiing()).isTrue();
		assertThat(zermatt.getNightskiing()).isFalse();
	}

	private Resort findByNamePrefix(String prefix) {
		return resortRepository.findByActiveTrueOrderByCountryAscNameAsc().stream()
				.filter(r -> r.getName().startsWith(prefix))
				.findFirst()
				.orElseThrow();
	}
}
