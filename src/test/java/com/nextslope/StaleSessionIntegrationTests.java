package com.nextslope;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import com.nextslope.support.TwoUserIntegrationTestBase;

/**
 * A session can outlive its backing user — the account is deleted (S-07) or the database is reset in
 * development. The session still carries an authentication, so without a guard the home page would
 * claim "signed in" while every user-scoped page fails its DB lookup with a confusing 401. The stale
 * session must instead be invalidated: the home page shows the signed-out view and gated pages
 * cleanly redirect to /login.
 */
class StaleSessionIntegrationTests extends TwoUserIntegrationTestBase {

	@Test
	void gatedPageRedirectsToLoginWhenSessionUserNoLongerExists() throws Exception {
		MockHttpSession session = loginAsUserA();
		userRepository.deleteAll();

		mockMvc.perform(get("/resorts").session(session))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void homePageShowsSignedOutWhenSessionUserNoLongerExists() throws Exception {
		MockHttpSession session = loginAsUserA();
		userRepository.deleteAll();

		mockMvc.perform(get("/").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("Signed in as"))));
	}
}
