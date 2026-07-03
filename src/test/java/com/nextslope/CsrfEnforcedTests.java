package com.nextslope;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nextslope.config.SecurityConfig;
import com.nextslope.profile.PreferenceProfileService;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.CurrentUserService;
import com.nextslope.user.UserRegistrationService;
import com.nextslope.user.UserRepository;
import com.nextslope.visited.VisitedResortService;

/**
 * Pins that CSRF protection stays enabled on the main chain: a state-changing POST
 * without a token is rejected, and the same POST with a valid token is accepted.
 * A regression that disables CSRF (e.g. {@code csrf(csrf -> csrf.disable())}) makes
 * the no-token case stop returning 403 and fails here.
 */
@WebMvcTest
@Import({SecurityConfig.class, AppUserDetailsService.class})
class CsrfEnforcedTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private UserRegistrationService userRegistrationService;

	@MockitoBean
	private com.nextslope.resort.ResortRepository resortRepository;

	@MockitoBean
	private com.nextslope.resort.ResortService resortService;

	@MockitoBean
	private PreferenceProfileService preferenceProfileService;

	@MockitoBean
	private VisitedResortService visitedResortService;

	@MockitoBean
	private CurrentUserService currentUserService;

	@MockitoBean
	private com.nextslope.recommendation.RecommendationService recommendationService;

	@Test
	void stateChangingPostWithoutCsrfTokenIsForbidden() throws Exception {
		mockMvc.perform(post("/logout"))
				.andExpect(status().isForbidden());
	}

	@Test
	void stateChangingPostWithCsrfTokenIsAccepted() throws Exception {
		mockMvc.perform(post("/logout").with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/?logout"));
	}
}
