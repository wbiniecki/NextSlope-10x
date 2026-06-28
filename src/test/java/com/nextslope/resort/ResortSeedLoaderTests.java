package com.nextslope.resort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.nextslope.profile.DifficultyBand;

@DataJpaTest
class ResortSeedLoaderTests {

	/** Curated European seed count — capped at <=150, every offered country carries >=3 active resorts. */
	static final long EXPECTED_SEED_COUNT = 150;

	@Autowired
	private ResortTestRepository resortRepository;

	@Test
	void seedsTheCuratedCatalogIntoAnEmptyDatabase() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository, false);

		loader.run(null);

		assertThat(resortRepository.count()).isEqualTo(EXPECTED_SEED_COUNT);
	}

	@Test
	void secondRunAgainstPopulatedTableInsertsNothing() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository, false);
		loader.run(null);

		assertThatCode(() -> loader.run(null)).doesNotThrowAnyException();

		assertThat(resortRepository.count()).isEqualTo(EXPECTED_SEED_COUNT);
	}

	@Test
	void everyOfferedCountryHasAtLeastThreeResorts() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository, false);
		loader.run(null);

		Map<String, Long> perCountry = resortRepository.findByActiveTrueOrderByCountryAscNameAsc().stream()
				.collect(Collectors.groupingBy(Resort::getCountry, Collectors.counting()));

		assertThat(perCountry).isNotEmpty();
		assertThat(perCountry).allSatisfy((country, count) ->
				assertThat(count).as("country %s resort count", country).isGreaterThanOrEqualTo(3));
	}

	@Test
	void everyDifficultyBandHasAtLeastOneNearestMatch() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository, false);
		loader.run(null);

		Map<DifficultyBand, Long> perBand = resortRepository.findByActiveTrueOrderByCountryAscNameAsc().stream()
				.collect(Collectors.groupingBy(
						r -> nearestBand(r.getDifficultyMix()), Collectors.counting()));

		assertThat(perBand.keySet()).containsExactlyInAnyOrder(DifficultyBand.values());
	}

	@Test
	void resyncUpdatesAChangedRowInPlaceWithoutAddingRows() {
		new ResortSeedLoader(resortRepository, false).run(null);
		Resort stale = resortRepository.findByExternalId(96L).orElseThrow();
		Long pk = stale.getId();
		stale.setName("STALE NAME");
		stale.setPrice(9999);
		resortRepository.saveAndFlush(stale);

		new ResortSeedLoader(resortRepository, true).run(null);

		Resort refreshed = resortRepository.findByExternalId(96L).orElseThrow();
		assertThat(refreshed.getId()).as("same row, updated in place").isEqualTo(pk);
		assertThat(refreshed.getName()).isEqualTo("Sölden");
		assertThat(refreshed.getPrice()).isEqualTo(53);
		assertThat(resortRepository.count()).isEqualTo(EXPECTED_SEED_COUNT);
	}

	@Test
	void resyncInsertsAMissingRow() {
		new ResortSeedLoader(resortRepository, false).run(null);
		Resort removed = resortRepository.findByExternalId(96L).orElseThrow();
		resortRepository.delete(removed);
		resortRepository.flush();
		assertThat(resortRepository.count()).isEqualTo(EXPECTED_SEED_COUNT - 1);

		new ResortSeedLoader(resortRepository, true).run(null);

		assertThat(resortRepository.findByExternalId(96L)).isPresent();
		assertThat(resortRepository.count()).isEqualTo(EXPECTED_SEED_COUNT);
	}

	@Test
	void resyncIsIdempotentAcrossReruns() {
		ResortSeedLoader resync = new ResortSeedLoader(resortRepository, true);
		resync.run(null);
		resync.run(null);

		assertThat(resortRepository.count()).isEqualTo(EXPECTED_SEED_COUNT);
	}

	@Test
	void resyncPreservesAdminDeactivatedActiveFlagWhileUpdatingFacts() {
		new ResortSeedLoader(resortRepository, false).run(null);
		Resort deactivated = resortRepository.findByExternalId(96L).orElseThrow();
		deactivated.setActive(false);
		deactivated.setPrice(9999);
		resortRepository.saveAndFlush(deactivated);

		new ResortSeedLoader(resortRepository, true).run(null);

		Resort refreshed = resortRepository.findByExternalId(96L).orElseThrow();
		assertThat(refreshed.getActive()).as("admin deactivation preserved").isFalse();
		assertThat(refreshed.getPrice()).as("fact columns still reconciled").isEqualTo(53);
	}

	private static DifficultyBand nearestBand(DifficultyMix mix) {
		Function<DifficultyBand, Integer> l1 = band -> {
			DifficultyMix target = band.toMix();
			return Math.abs(mix.easy() - target.easy())
					+ Math.abs(mix.medium() - target.medium())
					+ Math.abs(mix.hard() - target.hard());
		};
		DifficultyBand best = DifficultyBand.values()[0];
		for (DifficultyBand band : DifficultyBand.values()) {
			if (l1.apply(band) < l1.apply(best)) {
				best = band;
			}
		}
		return best;
	}

	@Test
	void preservesQuotedCommaSeasonAndUtf8Names() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository, false);
		loader.run(null);

		Resort tignes = findByNamePrefix("Tignes");
		assertThat(tignes.getSeason()).isEqualTo("November - May, June - August");

		boolean hasSolden = resortRepository.findByActiveTrueOrderByCountryAscNameAsc().stream()
				.anyMatch(r -> "Sölden".equals(r.getName()));
		assertThat(hasSolden).isTrue();
	}

	@Test
	void mapsYesNoColumnsToBooleans() {
		ResortSeedLoader loader = new ResortSeedLoader(resortRepository, false);
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
