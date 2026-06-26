package com.nextslope.visited;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;
import com.nextslope.support.TwoUserIntegrationTestBase;
import com.nextslope.support.UserFixtures;

/**
 * Privacy guardrail: a user's visited list is visible to and mutable by only that user. The toggle
 * route is principal-scoped (no user id in the path), so there is no cross-user URL to forge — the
 * assertion is <em>isolation</em> (the same shape as {@code PreferenceProfileOwnershipIntegrationTests}),
 * not a forbidden response. See the {@code assertWrongOwnerDenied} seam note in the plan.
 */
class VisitedResortOwnershipIntegrationTests extends TwoUserIntegrationTestBase {

	@Autowired
	private ResortRepository resortRepository;

	@Autowired
	private VisitedResortRepository visitedResortRepository;

	private Long resortXId;

	@BeforeEach
	void seedResorts() {
		visitedResortRepository.deleteAll();
		resortRepository.deleteAll();
		resortXId = resortRepository
				.save(Resort.builder().name("Chamonix").country("France").active(true).build())
				.getId();
		resortRepository.save(Resort.builder().name("Sölden").country("Austria").active(true).build());
	}

	@AfterEach
	void clearVisitedAndResorts() {
		visitedResortRepository.deleteAll();
		resortRepository.deleteAll();
	}

	@Test
	void aMarkIsVisibleOnlyToTheUserWhoCreatedIt() throws Exception {
		Long userIdA = userRepository.findByEmail(UserFixtures.USER_A_EMAIL).orElseThrow().getId();
		Long userIdB = userRepository.findByEmail(UserFixtures.USER_B_EMAIL).orElseThrow().getId();
		Long adminId = userRepository.findByEmail(UserFixtures.ADMIN_EMAIL).orElseThrow().getId();

		MockHttpSession sessionA = loginAsUserA();
		mockMvc.perform(post("/resorts/{id}/visited", resortXId)
						.session(sessionA)
						.with(csrf()))
				.andExpect(status().isOk());

		// Exactly A's mark exists; neither B nor the admin can see it.
		assertThat(visitedResortRepository.existsByUserIdAndResortId(userIdA, resortXId)).isTrue();
		assertThat(visitedResortRepository.findResortIdsByUserId(userIdA)).containsExactly(resortXId);
		assertThat(visitedResortRepository.findResortIdsByUserId(userIdB)).isEmpty();
		assertThat(visitedResortRepository.findResortIdsByUserId(adminId)).isEmpty();
		assertThat(visitedResortRepository.count()).isEqualTo(1);
	}

	@Test
	void oneUsersMarksNeverAffectAnother() throws Exception {
		Long userIdA = userRepository.findByEmail(UserFixtures.USER_A_EMAIL).orElseThrow().getId();
		Long userIdB = userRepository.findByEmail(UserFixtures.USER_B_EMAIL).orElseThrow().getId();

		MockHttpSession sessionA = loginAsUserA();
		mockMvc.perform(post("/resorts/{id}/visited", resortXId)
						.session(sessionA)
						.with(csrf()))
				.andExpect(status().isOk());

		// B unmarking the same resort id is a no-op against B's (empty) list — A's mark is untouched.
		MockHttpSession sessionB = loginAsUserB();
		mockMvc.perform(post("/resorts/{id}/visited", resortXId)
						.session(sessionB)
						.with(csrf()))
				.andExpect(status().isOk());

		assertThat(visitedResortRepository.findResortIdsByUserId(userIdA)).containsExactly(resortXId);
		assertThat(visitedResortRepository.findResortIdsByUserId(userIdB)).containsExactly(resortXId);
		assertThat(visitedResortRepository.existsByUserIdAndResortId(userIdA, resortXId)).isTrue();
	}
}
