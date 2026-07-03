package com.nextslope.resort;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class ResortRepositoryTests {

	@Autowired
	private ResortRepository resortRepository;

	private static Resort.ResortBuilder resort(String name, String country) {
		return Resort.builder()
				.name(name)
				.country(country)
				.beginnerSlopes(10)
				.intermediateSlopes(10)
				.difficultSlopes(10)
				.active(true);
	}

	@Test
	void findsActiveResortsOrderedByCountryThenName() {
		resortRepository.save(resort("Verbier", "Switzerland").build());
		resortRepository.save(resort("Zermatt", "Switzerland").build());
		resortRepository.save(resort("Ischgl", "Austria").build());
		resortRepository.save(resort("Alpe d'Huez", "France").build());

		List<Resort> ordered = resortRepository.findByActiveTrueOrderByCountryAscNameAsc();

		assertThat(ordered).extracting(Resort::getCountry)
				.containsExactly("Austria", "France", "Switzerland", "Switzerland");
		assertThat(ordered).extracting(Resort::getName)
				.containsExactly("Ischgl", "Alpe d'Huez", "Verbier", "Zermatt");
	}

	@Test
	void browseQueryExcludesInactiveResorts() {
		resortRepository.save(resort("Active Resort", "Austria").build());
		resortRepository.save(resort("Hidden Resort", "Austria").active(false).build());

		List<Resort> ordered = resortRepository.findByActiveTrueOrderByCountryAscNameAsc();

		assertThat(ordered).extracting(Resort::getName).containsExactly("Active Resort");
	}

	@Test
	void findAllByOrderByCountryAscNameAscReturnsActiveAndInactiveInOrder() {
		resortRepository.save(resort("Active Resort", "Austria").build());
		resortRepository.save(resort("Hidden Resort", "Austria").active(false).build());
		resortRepository.save(resort("Alpe d'Huez", "France").build());

		List<Resort> ordered = resortRepository.findAllByOrderByCountryAscNameAsc();

		assertThat(ordered).extracting(Resort::getCountry)
				.containsExactly("Austria", "Austria", "France");
		assertThat(ordered).extracting(Resort::getName)
				.containsExactly("Active Resort", "Hidden Resort", "Alpe d'Huez");
	}

	@Test
	void findByIdAndActiveTrueReturnsActiveResort() {
		Resort saved = resortRepository.save(resort("Sölden", "Austria").build());

		Optional<Resort> found = resortRepository.findByIdAndActiveTrue(saved.getId());

		assertThat(found).isPresent();
		assertThat(found.get().getName()).isEqualTo("Sölden");
		assertThat(found.get().getCreatedAt()).isNotNull();
		assertThat(found.get().getUpdatedAt()).isNotNull();
	}

	@Test
	void findByIdAndActiveTrueExcludesInactiveResort() {
		Resort saved = resortRepository.save(resort("Hidden", "Austria").active(false).build());

		Optional<Resort> found = resortRepository.findByIdAndActiveTrue(saved.getId());

		assertThat(found).isEmpty();
	}
}
