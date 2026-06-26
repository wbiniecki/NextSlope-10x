package com.nextslope.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;
import com.nextslope.support.TwoUserIntegrationTestBase;
import com.nextslope.support.UserFixtures;

/**
 * Privacy guardrail: {@code /profile} is principal-scoped (no id in the path), so the assertion is
 * <em>isolation</em> — two users' profiles never bleed into one another — not a forbidden response.
 */
class PreferenceProfileOwnershipIntegrationTests extends TwoUserIntegrationTestBase {

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

	@Autowired
	private ResortRepository resortRepository;

	@BeforeEach
	void seedRegionVocabulary() {
		preferenceProfileRepository.deleteAll();
		resortRepository.deleteAll();
		resortRepository.save(Resort.builder().name("Chamonix").country("France").active(true).build());
		resortRepository.save(Resort.builder().name("Sölden").country("Austria").active(true).build());
	}

	@AfterEach
	void clearProfilesAndResorts() {
		preferenceProfileRepository.deleteAll();
		resortRepository.deleteAll();
	}

	@Test
	void eachUserOnlyReadsAndWritesTheirOwnProfile() throws Exception {
		MockHttpSession sessionA = loginAsUserA();
		mockMvc.perform(post("/profile")
						.session(sessionA)
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("experienceLevel", "BEGINNER")
						.param("difficultyBand", "MOSTLY_EASY")
						.param("noveltyPreference", "NEW_ONLY")
						.param("anyRegion", "false")
						.param("regionCountries", "France"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/resorts"));

		MockHttpSession sessionB = loginAsUserB();
		mockMvc.perform(post("/profile")
						.session(sessionB)
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("experienceLevel", "ADVANCED")
						.param("difficultyBand", "MOSTLY_HARD")
						.param("noveltyPreference", "REVISIT_OKAY")
						.param("anyRegion", "false")
						.param("regionCountries", "Austria"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/resorts"));

		Long userIdA = userRepository.findByEmail(UserFixtures.USER_A_EMAIL).orElseThrow().getId();
		Long userIdB = userRepository.findByEmail(UserFixtures.USER_B_EMAIL).orElseThrow().getId();

		PreferenceProfile profileA = preferenceProfileRepository.findByUserId(userIdA).orElseThrow();
		PreferenceProfile profileB = preferenceProfileRepository.findByUserId(userIdB).orElseThrow();

		// A's row carries only A's values — untouched by B's save.
		assertThat(profileA.getExperienceLevel()).isEqualTo(ExperienceLevel.BEGINNER);
		assertThat(profileA.getDifficultyBand()).isEqualTo(DifficultyBand.MOSTLY_EASY);
		assertThat(profileA.getNoveltyPreference()).isEqualTo(NoveltyPreference.NEW_ONLY);
		assertThat(profileA.getRegionCountries()).containsExactly("France");

		// B's row is fully independent.
		assertThat(profileB.getExperienceLevel()).isEqualTo(ExperienceLevel.ADVANCED);
		assertThat(profileB.getDifficultyBand()).isEqualTo(DifficultyBand.MOSTLY_HARD);
		assertThat(profileB.getNoveltyPreference()).isEqualTo(NoveltyPreference.REVISIT_OKAY);
		assertThat(profileB.getRegionCountries()).containsExactly("Austria");

		// Exactly two rows: B's save created its own, never overwrote A's.
		assertThat(preferenceProfileRepository.count()).isEqualTo(2);
	}
}
