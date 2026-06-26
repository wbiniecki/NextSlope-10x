package com.nextslope.visited;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class VisitedResortRepositoryPostgresTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Autowired
	private VisitedResortRepository visitedResortRepository;

	@Test
	void migrationAppliesAndQueriesBehaveOnRealPostgres() {
		// User/resort ids are arbitrary on this prod-engine context; visited_resorts has no FK to
		// users/resorts, so any ids exercise the mapping + unique constraint.
		VisitedResort saved = visitedResortRepository.save(
				VisitedResort.builder().userId(700001L).resortId(800001L).build());

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(visitedResortRepository.existsByUserIdAndResortId(700001L, 800001L)).isTrue();

		assertThatThrownBy(() -> visitedResortRepository.saveAndFlush(
				VisitedResort.builder().userId(700001L).resortId(800001L).build()))
				.isInstanceOf(DataIntegrityViolationException.class);

		Set<Long> ids = visitedResortRepository.findResortIdsByUserId(700001L);
		assertThat(ids).contains(800001L);

		assertThat(visitedResortRepository.deleteByUserIdAndResortId(700001L, 800001L)).isEqualTo(1L);
		assertThat(visitedResortRepository.existsByUserIdAndResortId(700001L, 800001L)).isFalse();
	}
}
