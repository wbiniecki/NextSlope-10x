package com.nextslope.resort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.support.TwoUserIntegrationTestBase;
import com.nextslope.visited.VisitedResortRepository;

/**
 * FR-013 end-to-end: after an admin deactivates a resort it disappears from the {@code /resorts}
 * browse list and from {@code /recommend} candidate results, but a user's prior visited mark on it
 * can still be cleared; reactivation restores it to browse. Mirrors the existing full-stack
 * {@code @SpringBootTest} integration tests ({@code RecommendationOwnershipIntegrationTests},
 * {@code VisitedResortOwnershipIntegrationTests}).
 */
class ResortDeactivationIntegrationTests extends TwoUserIntegrationTestBase {

	@Autowired
	private ResortRepository resortRepository;

	@Autowired
	private VisitedResortRepository visitedResortRepository;

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

	// Three France resorts so a France-region profile yields a full three-card recommendation that
	// includes TARGET while it is active; deactivating TARGET must drop it from both surfaces.
	private static final String TARGET = "Chamonix";
	private static final String OTHER = "Tignes";

	private Long targetId;

	@BeforeEach
	void seedCatalog() {
		preferenceProfileRepository.deleteAll();
		visitedResortRepository.deleteAll();
		resortRepository.deleteAll();
		targetId = saveResort(TARGET, "France");
		saveResort(OTHER, "France");
		saveResort("Morzine", "France");
	}

	@AfterEach
	void clearCatalog() {
		preferenceProfileRepository.deleteAll();
		visitedResortRepository.deleteAll();
		resortRepository.deleteAll();
	}

	private Long saveResort(String name, String country) {
		return resortRepository
				.save(Resort.builder().name(name).country(country).active(true).build())
				.getId();
	}

	private void saveFranceProfile(MockHttpSession session) throws Exception {
		mockMvc.perform(post("/profile")
						.session(session)
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("experienceLevel", "INTERMEDIATE")
						.param("difficultyBand", "BALANCED")
						.param("noveltyPreference", "REVISIT_OKAY")
						.param("anyRegion", "false")
						.param("regionCountries", "France"))
				.andExpect(status().is3xxRedirection());
	}

	private String browseBody(MockHttpSession session) throws Exception {
		return mockMvc.perform(get("/resorts").session(session))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private String recommendBody(MockHttpSession session) throws Exception {
		return mockMvc.perform(post("/recommend").session(session).with(csrf()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	@Test
	void deactivationHidesResortFromBrowseAndRecommendationsWhileVisitedMarkSurvivesAndReactivationRestores()
			throws Exception {
		MockHttpSession sessionA = loginAsUserA();
		saveFranceProfile(sessionA);
		Long userIdA = userRepository.findByEmail(com.nextslope.support.UserFixtures.USER_A_EMAIL)
				.orElseThrow().getId();

		// Baseline: TARGET is browsable and recommended while active.
		assertThat(browseBody(sessionA)).contains(TARGET);
		assertThat(recommendBody(sessionA)).contains(TARGET);

		// User A marks TARGET visited while it is still active.
		mockMvc.perform(post("/resorts/{id}/visited", targetId).session(sessionA).with(csrf()))
				.andExpect(status().isOk());
		assertThat(visitedResortRepository.existsByUserIdAndResortId(userIdA, targetId)).isTrue();

		// Admin deactivates TARGET through the gated toggle endpoint.
		MockHttpSession sessionAdmin = loginAsAdmin();
		mockMvc.perform(post("/admin/resorts/{id}/active", targetId).session(sessionAdmin).with(csrf()))
				.andExpect(status().isOk());
		assertThat(resortRepository.findById(targetId).orElseThrow().getActive()).isFalse();

		// It is now gone from browse and from recommendations, but OTHER still shows.
		String browseAfter = browseBody(sessionA);
		assertThat(browseAfter).doesNotContain(TARGET);
		assertThat(browseAfter).contains(OTHER);
		assertThat(recommendBody(sessionA)).doesNotContain(TARGET);

		// The prior visited mark on the now-inactive resort can still be cleared (FR-013).
		mockMvc.perform(post("/resorts/{id}/visited", targetId).session(sessionA).with(csrf()))
				.andExpect(status().isOk());
		assertThat(visitedResortRepository.existsByUserIdAndResortId(userIdA, targetId)).isFalse();

		// Reactivation restores it to browse.
		mockMvc.perform(post("/admin/resorts/{id}/active", targetId).session(sessionAdmin).with(csrf()))
				.andExpect(status().isOk());
		assertThat(browseBody(sessionA)).contains(TARGET);
	}
}
