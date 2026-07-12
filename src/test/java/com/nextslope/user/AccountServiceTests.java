package com.nextslope.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.nextslope.profile.DifficultyBand;
import com.nextslope.profile.ExperienceLevel;
import com.nextslope.profile.NoveltyPreference;
import com.nextslope.profile.PreferenceProfile;
import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.visited.VisitedResort;
import com.nextslope.visited.VisitedResortRepository;

/**
 * H2 half of the dual-engine cascade proof (S-07). The authoritative FK-ordering /
 * element-collection check runs on real Postgres in {@link AccountServicePostgresTests}.
 */
@DataJpaTest
@Import(AccountService.class)
class AccountServiceTests {

	@Autowired
	private AccountService accountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

	@Autowired
	private VisitedResortRepository visitedResortRepository;

	@Autowired
	private TestEntityManager entityManager;

	private Long persistUser(String email) {
		User user = User.builder()
				.email(email)
				.passwordHash("hashed-secret")
				.role(User.Role.USER)
				.build();
		return entityManager.persistAndGetId(user, Long.class);
	}

	private Long persistProfile(Long userId, Set<String> regions) {
		PreferenceProfile profile = preferenceProfileRepository.saveAndFlush(PreferenceProfile.builder()
				.userId(userId)
				.experienceLevel(ExperienceLevel.INTERMEDIATE)
				.difficultyBand(DifficultyBand.BALANCED)
				.noveltyPreference(NoveltyPreference.REVISIT_OKAY)
				.regionCountries(regions)
				.build());
		return profile.getId();
	}

	private long countRegionRows(Long profileId) {
		return ((Number) entityManager.getEntityManager()
				.createNativeQuery("select count(*) from preference_profile_regions where profile_id = :profileId")
				.setParameter("profileId", profileId)
				.getSingleResult()).longValue();
	}

	@Test
	void deleteAccountRemovesUserProfileRegionsAndVisitedRows() {
		Long userId = persistUser("doomed@nextslope.test");
		Long profileId = persistProfile(userId, Set.of("France", "Austria"));
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(userId).resortId(10L).build());
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(userId).resortId(20L).build());

		accountService.deleteAccount(userId);
		entityManager.flush();
		entityManager.clear();

		assertThat(userRepository.findById(userId)).isEmpty();
		assertThat(preferenceProfileRepository.findByUserId(userId)).isEmpty();
		assertThat(visitedResortRepository.findResortIdsByUserId(userId)).isEmpty();
		assertThat(countRegionRows(profileId)).isZero();
	}

	@Test
	void deleteAccountLeavesOtherUsersDataUntouched() {
		Long doomedId = persistUser("doomed@nextslope.test");
		persistProfile(doomedId, Set.of("France"));
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(doomedId).resortId(10L).build());

		Long survivorId = persistUser("survivor@nextslope.test");
		Long survivorProfileId = persistProfile(survivorId, Set.of("Switzerland", "Italy"));
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(survivorId).resortId(30L).build());

		accountService.deleteAccount(doomedId);
		entityManager.flush();
		entityManager.clear();

		assertThat(userRepository.findById(survivorId)).isPresent();
		PreferenceProfile survivorProfile = preferenceProfileRepository.findByUserId(survivorId).orElseThrow();
		assertThat(survivorProfile.getRegionCountries()).containsExactlyInAnyOrder("Switzerland", "Italy");
		assertThat(visitedResortRepository.findResortIdsByUserId(survivorId)).containsExactly(30L);
		assertThat(countRegionRows(survivorProfileId)).isEqualTo(2);
	}

	@Test
	void deleteAccountSucceedsForUserWithoutProfileOrVisitedRows() {
		Long userId = persistUser("bare@nextslope.test");

		accountService.deleteAccount(userId);
		entityManager.flush();
		entityManager.clear();

		assertThat(userRepository.findById(userId)).isEmpty();
	}
}
