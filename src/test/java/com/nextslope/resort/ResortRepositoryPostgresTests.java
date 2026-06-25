package com.nextslope.resort;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class ResortRepositoryPostgresTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Autowired
	private ResortRepository resortRepository;

	@Test
	void migrationAppliesAndMappingValidatesOnRealPostgres() {
		Resort saved = resortRepository.save(Resort.builder()
				.externalId(57L)
				.name("Tignes - Val d'Isère")
				.country("France")
				.continent("Europe")
				.beginnerSlopes(170)
				.intermediateSlopes(78)
				.difficultSlopes(52)
				.totalSlopes(300)
				.active(true)
				.build());

		Optional<Resort> found = resortRepository.findByIdAndActiveTrue(saved.getId());

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isNotNull();
		assertThat(found.get().getName()).isEqualTo("Tignes - Val d'Isère");
		assertThat(found.get().getExternalId()).isEqualTo(57L);
		assertThat(found.get().getCreatedAt()).isNotNull();
		assertThat(found.get().getUpdatedAt()).isNotNull();

		List<Resort> ordered = resortRepository.findByActiveTrueOrderByCountryAscNameAsc();
		assertThat(ordered).extracting(Resort::getName).contains("Tignes - Val d'Isère");
	}
}
