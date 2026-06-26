package com.nextslope.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import com.nextslope.user.User;

@DataJpaTest
class PreferenceProfileRepositoryTests {

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

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

	@Test
	void roundTripsAllAxesAndRegionSet() {
		Long userId = persistUser("rider@nextslope.test");

		PreferenceProfile profile = PreferenceProfile.builder()
				.userId(userId)
				.experienceLevel(ExperienceLevel.ADVANCED)
				.difficultyBand(DifficultyBand.MOSTLY_HARD)
				.noveltyPreference(NoveltyPreference.NEW_ONLY)
				.regionCountries(Set.of("France", "Austria"))
				.build();
		preferenceProfileRepository.saveAndFlush(profile);

		entityManager.clear();

		Optional<PreferenceProfile> found = preferenceProfileRepository.findByUserId(userId);

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isNotNull();
		assertThat(found.get().getExperienceLevel()).isEqualTo(ExperienceLevel.ADVANCED);
		assertThat(found.get().getDifficultyBand()).isEqualTo(DifficultyBand.MOSTLY_HARD);
		assertThat(found.get().getNoveltyPreference()).isEqualTo(NoveltyPreference.NEW_ONLY);
		assertThat(found.get().getRegionCountries()).containsExactlyInAnyOrder("France", "Austria");
		assertThat(found.get().getCreatedAt()).isNotNull();
		assertThat(found.get().getUpdatedAt()).isNotNull();
	}

	@Test
	void preferredMixDelegatesToDifficultyBand() {
		Long userId = persistUser("mixer@nextslope.test");
		preferenceProfileRepository.saveAndFlush(PreferenceProfile.builder()
				.userId(userId)
				.experienceLevel(ExperienceLevel.BEGINNER)
				.difficultyBand(DifficultyBand.MOSTLY_EASY)
				.noveltyPreference(NoveltyPreference.REVISIT_OKAY)
				.regionCountries(Set.of())
				.build());

		PreferenceProfile found = preferenceProfileRepository.findByUserId(userId).orElseThrow();

		assertThat(found.getPreferredMix()).isEqualTo(DifficultyBand.MOSTLY_EASY.toMix());
	}

	@Test
	void uniqueUserIdRejectsSecondProfileForSameUser() {
		Long userId = persistUser("dup@nextslope.test");
		preferenceProfileRepository.saveAndFlush(PreferenceProfile.builder()
				.userId(userId)
				.experienceLevel(ExperienceLevel.INTERMEDIATE)
				.difficultyBand(DifficultyBand.BALANCED)
				.noveltyPreference(NoveltyPreference.REVISIT_OKAY)
				.regionCountries(Set.of())
				.build());

		assertThatThrownBy(() -> preferenceProfileRepository.saveAndFlush(PreferenceProfile.builder()
				.userId(userId)
				.experienceLevel(ExperienceLevel.ADVANCED)
				.difficultyBand(DifficultyBand.MOSTLY_HARD)
				.noveltyPreference(NoveltyPreference.NEW_ONLY)
				.regionCountries(Set.of())
				.build()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
