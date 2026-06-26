package com.nextslope.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.nextslope.user.User;
import com.nextslope.user.UserRepository;

@SpringBootTest
@Testcontainers
class PreferenceProfileRepositoryPostgresTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

	@Autowired
	private UserRepository userRepository;

	@Test
	void migrationAppliesAndProfileRoundTripsOnRealPostgres() {
		User user = userRepository.save(User.builder()
				.email("pg-rider@nextslope.test")
				.passwordHash("hashed-secret")
				.role(User.Role.USER)
				.build());

		PreferenceProfile saved = preferenceProfileRepository.save(PreferenceProfile.builder()
				.userId(user.getId())
				.experienceLevel(ExperienceLevel.INTERMEDIATE)
				.difficultyBand(DifficultyBand.BALANCED)
				.noveltyPreference(NoveltyPreference.NEW_ONLY)
				.regionCountries(Set.of("Switzerland", "Italy"))
				.build());

		Optional<PreferenceProfile> found = preferenceProfileRepository.findByUserId(user.getId());

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(saved.getId());
		assertThat(found.get().getDifficultyBand()).isEqualTo(DifficultyBand.BALANCED);
		assertThat(found.get().getRegionCountries()).containsExactlyInAnyOrder("Switzerland", "Italy");
		assertThat(found.get().getCreatedAt()).isNotNull();
		assertThat(found.get().getUpdatedAt()).isNotNull();
	}
}
