package com.nextslope.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.nextslope.profile.DifficultyBand;
import com.nextslope.profile.ExperienceLevel;
import com.nextslope.profile.NoveltyPreference;
import com.nextslope.profile.PreferenceProfile;
import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.visited.VisitedResort;
import com.nextslope.visited.VisitedResortRepository;

/**
 * The authoritative half of the dual-engine cascade proof (S-07): real Postgres enforces the
 * {@code preference_profiles} → {@code users} FK and the regions element-collection FK, so a wrong
 * deletion order or a bulk profile delete fails here even when H2 lets it slide.
 */
@SpringBootTest
@Testcontainers
class AccountServicePostgresTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

	@Autowired
	private VisitedResortRepository visitedResortRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void cleanUp() {
		// Children before parents: profiles must go as managed entities (regions cascade) before users.
		visitedResortRepository.deleteAll();
		preferenceProfileRepository.deleteAll();
		userRepository.deleteAll();
	}

	private Long saveUser(String email) {
		return userRepository.save(User.builder()
				.email(email)
				.passwordHash("hashed-secret")
				.role(User.Role.USER)
				.build()).getId();
	}

	private Long saveProfile(Long userId, Set<String> regions) {
		return preferenceProfileRepository.save(PreferenceProfile.builder()
				.userId(userId)
				.experienceLevel(ExperienceLevel.ADVANCED)
				.difficultyBand(DifficultyBand.MOSTLY_HARD)
				.noveltyPreference(NoveltyPreference.NEW_ONLY)
				.regionCountries(regions)
				.build()).getId();
	}

	private long countRegionRows(Long profileId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from preference_profile_regions where profile_id = ?", Long.class, profileId);
	}

	@Test
	void deleteAccountCascadesAcrossAllFourTablesOnRealPostgres() {
		Long userId = saveUser("pg-doomed@nextslope.test");
		Long profileId = saveProfile(userId, Set.of("France", "Austria"));
		visitedResortRepository.save(VisitedResort.builder().userId(userId).resortId(10L).build());
		visitedResortRepository.save(VisitedResort.builder().userId(userId).resortId(20L).build());

		accountService.deleteAccount(userId);

		assertThat(userRepository.findById(userId)).isEmpty();
		assertThat(preferenceProfileRepository.findByUserId(userId)).isEmpty();
		assertThat(visitedResortRepository.findResortIdsByUserId(userId)).isEmpty();
		assertThat(countRegionRows(profileId)).isZero();
	}

	@Test
	void deleteAccountLeavesOtherUsersDataUntouchedOnRealPostgres() {
		Long doomedId = saveUser("pg-doomed@nextslope.test");
		saveProfile(doomedId, Set.of("France"));
		visitedResortRepository.save(VisitedResort.builder().userId(doomedId).resortId(10L).build());

		Long survivorId = saveUser("pg-survivor@nextslope.test");
		Long survivorProfileId = saveProfile(survivorId, Set.of("Switzerland", "Italy"));
		visitedResortRepository.save(VisitedResort.builder().userId(survivorId).resortId(30L).build());

		accountService.deleteAccount(doomedId);

		assertThat(userRepository.findById(survivorId)).isPresent();
		assertThat(preferenceProfileRepository.findByUserId(survivorId)).isPresent();
		assertThat(visitedResortRepository.findResortIdsByUserId(survivorId)).containsExactly(30L);
		assertThat(countRegionRows(survivorProfileId)).isEqualTo(2);
	}

	@Test
	void deleteAccountSucceedsForBareUserOnRealPostgres() {
		Long userId = saveUser("pg-bare@nextslope.test");

		accountService.deleteAccount(userId);

		assertThat(userRepository.findById(userId)).isEmpty();
	}
}
