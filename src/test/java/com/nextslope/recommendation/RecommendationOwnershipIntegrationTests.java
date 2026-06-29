package com.nextslope.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;
import com.nextslope.support.TwoUserIntegrationTestBase;

/**
 * Privacy guardrail: {@code /recommend} is principal-scoped (no user id in the path), so the
 * assertion is <em>isolation</em> — a request returns the calling principal's own recommendation,
 * never another user's — not a forbidden response. Mirrors
 * {@code PreferenceProfileOwnershipIntegrationTests}; not a 403 / {@code assertWrongOwnerDenied} check.
 */
class RecommendationOwnershipIntegrationTests extends TwoUserIntegrationTestBase {

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

	@Autowired
	private ResortRepository resortRepository;

	// France-only resort A's profile selects; must never surface for B or the (profile-less) admin.
	private static final String FRANCE_ONLY = "Chamonix";
	// An Austria resort B's profile selects; proves B's own profile drives B's result.
	private static final String AUSTRIA_PICK = "Mayrhofen";

	@BeforeEach
	void seedCatalog() {
		preferenceProfileRepository.deleteAll();
		resortRepository.deleteAll();
		// Three per region so a region-filtered profile yields a full three-card result, not sparse.
		saveResort(FRANCE_ONLY, "France");
		saveResort("Tignes", "France");
		saveResort("Morzine", "France");
		saveResort(AUSTRIA_PICK, "Austria");
		saveResort("Schladming", "Austria");
		saveResort("Kitzbuhel", "Austria");
	}

	@AfterEach
	void clearCatalog() {
		preferenceProfileRepository.deleteAll();
		resortRepository.deleteAll();
	}

	private void saveResort(String name, String country) {
		resortRepository.save(Resort.builder().name(name).country(country).active(true).build());
	}

	private void saveProfileFor(MockHttpSession session, String region) throws Exception {
		mockMvc.perform(post("/profile")
						.session(session)
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("experienceLevel", "INTERMEDIATE")
						.param("difficultyBand", "BALANCED")
						.param("noveltyPreference", "REVISIT_OKAY")
						.param("anyRegion", "false")
						.param("regionCountries", region))
				.andExpect(status().is3xxRedirection());
	}

	@Test
	void recommendationReflectsTheCallingPrincipalsOwnProfileNeverAnothersResult() throws Exception {
		MockHttpSession sessionA = loginAsUserA();
		saveProfileFor(sessionA, "France");

		MockHttpSession sessionB = loginAsUserB();
		saveProfileFor(sessionB, "Austria");

		// B's recommendation is computed from B's own (Austria) profile — A's France-only pick never leaks.
		String bodyForB = mockMvc.perform(post("/recommend").session(sessionB).with(csrf()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(bodyForB).contains(AUSTRIA_PICK);
		assertThat(bodyForB).doesNotContain(FRANCE_ONLY);
	}

	@Test
	void aProfilelessAdminGetsItsOwnNoProfileStateNotAnotherUsersResult() throws Exception {
		MockHttpSession sessionA = loginAsUserA();
		saveProfileFor(sessionA, "France");

		// The admin never set a profile, so it gets its own no-profile prompt — never A's France result.
		MockHttpSession sessionAdmin = loginAsAdmin();
		String bodyForAdmin = mockMvc.perform(post("/recommend").session(sessionAdmin).with(csrf()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(bodyForAdmin).contains("Set up your preference profile");
		assertThat(bodyForAdmin).doesNotContain(FRANCE_ONLY);
	}
}
