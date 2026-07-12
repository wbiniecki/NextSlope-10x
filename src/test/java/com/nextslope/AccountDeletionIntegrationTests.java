package com.nextslope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import com.nextslope.profile.DifficultyBand;
import com.nextslope.profile.ExperienceLevel;
import com.nextslope.profile.NoveltyPreference;
import com.nextslope.profile.PreferenceProfile;
import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.support.TwoUserIntegrationTestBase;
import com.nextslope.support.UserFixtures;
import com.nextslope.visited.VisitedResort;
import com.nextslope.visited.VisitedResortRepository;

/**
 * End-to-end proof of the S-07 slice on a live context: danger zone → confirm page → delete POST →
 * every trace of user A gone, user B untouched, A's session dead, success banner rendered
 * signed-out.
 */
class AccountDeletionIntegrationTests extends TwoUserIntegrationTestBase {

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

	@Autowired
	private VisitedResortRepository visitedResortRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// Subclass @AfterEach runs before the base class's userRepository.deleteAll(); surviving
	// profile rows would violate the preference_profiles FK there, so clear children first.
	@AfterEach
	void clearProfilesAndVisitedRows() {
		preferenceProfileRepository.deleteAll();
		visitedResortRepository.deleteAll();
	}

	private Long seedProfileAndVisited(String email, Set<String> regions, long... resortIds) {
		Long userId = userRepository.findByEmail(email).orElseThrow().getId();
		preferenceProfileRepository.save(PreferenceProfile.builder()
				.userId(userId)
				.experienceLevel(ExperienceLevel.INTERMEDIATE)
				.difficultyBand(DifficultyBand.BALANCED)
				.noveltyPreference(NoveltyPreference.REVISIT_OKAY)
				.regionCountries(regions)
				.build());
		for (long resortId : resortIds) {
			visitedResortRepository.save(VisitedResort.builder().userId(userId).resortId(resortId).build());
		}
		return userId;
	}

	private long countRegionRowsForUser(Long userId) {
		Long count = jdbcTemplate.queryForObject(
				"select count(*) from preference_profile_regions r "
						+ "join preference_profiles p on r.profile_id = p.id where p.user_id = ?",
				Long.class, userId);
		return count == null ? 0 : count;
	}

	@Test
	void deleteFlowRemovesAllUserDataKillsSessionAndLeavesOtherUserIntact() throws Exception {
		Long userIdA = seedProfileAndVisited(UserFixtures.USER_A_EMAIL, Set.of("France", "Austria"), 10L, 20L);
		Long userIdB = seedProfileAndVisited(UserFixtures.USER_B_EMAIL, Set.of("Switzerland"), 30L);

		MockHttpSession sessionA = loginAsUserA();

		// Entry point: the profile page surfaces the danger zone linking to the confirm page.
		mockMvc.perform(get("/profile").session(sessionA))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Danger zone")))
				.andExpect(content().string(containsString("/account/delete")));

		mockMvc.perform(get("/account/delete").session(sessionA))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Permanently delete my account")));

		mockMvc.perform(post("/account/delete").session(sessionA).with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/?deleted"));

		// Every trace of A is gone from all four tables.
		assertThat(userRepository.findByEmail(UserFixtures.USER_A_EMAIL)).isEmpty();
		assertThat(preferenceProfileRepository.findByUserId(userIdA)).isEmpty();
		assertThat(visitedResortRepository.findResortIdsByUserId(userIdA)).isEmpty();
		assertThat(countRegionRowsForUser(userIdA)).isZero();

		// B's data is fully intact.
		assertThat(userRepository.findByEmail(UserFixtures.USER_B_EMAIL)).isPresent();
		assertThat(preferenceProfileRepository.findByUserId(userIdB)).isPresent();
		assertThat(visitedResortRepository.findResortIdsByUserId(userIdB)).containsExactly(30L);
		assertThat(countRegionRowsForUser(userIdB)).isEqualTo(1);

		// A's old session is dead: gated pages bounce to /login.
		mockMvc.perform(get("/resorts").session(sessionA))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));

		// The landing page confirms deletion, rendered signed-out.
		mockMvc.perform(get("/").param("deleted", "").session(sessionA))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Your account has been deleted.")))
				.andExpect(content().string(not(containsString("Signed in as"))));
	}
}
