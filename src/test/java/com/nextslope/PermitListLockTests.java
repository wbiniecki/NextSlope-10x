package com.nextslope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
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
 * Risk #4 regression net: pins the public surface and proves a real gated route.
 *
 * <p>Supersedes {@code RouteGatingTests} — it carries the {@code /whatever}
 * anonymous catch-all canary forward and replaces the synthetic 404 proxy (impl
 * review finding F13) with a real gated route ({@code /error}).
 *
 * <p>Coverage is sample-based by design (the plan deliberately avoids reflecting
 * over {@code SecurityFilterChain}): each future slice must add its own
 * route-specific gating assertion as it introduces real routes. Keep the
 * must-stay-gated prefix list aligned with the roadmap.
 */
@WebMvcTest
@Import({SecurityConfig.class, AppUserDetailsService.class})
class PermitListLockTests {

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
	private com.nextslope.recommendation.RecommendationService recommendationService;

	@MockitoBean
	private CurrentUserService currentUserService;

	@BeforeEach
	void mockUserExists() {
		when(userRepository.existsByEmail(anyString())).thenReturn(true);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/", "/index", "/login", "/signup", "/actuator/health", "/css/app.css",
			"/js/app.js", "/webjars/x"})
	void permitListedPathsStayPublicForAnonymous(String path) throws Exception {
		// One representative path per permit-listed pattern. Whatever the handler
		// returns (200, 404 for a missing static resource, ...), an anonymous request
		// to a public path must never be bounced to the login page.
		mockMvc.perform(get(path))
				.andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
						.as("permit-listed path %s must not redirect anonymous users to /login", path)
						.isNotEqualTo("/login"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/error", "/profile", "/visited", "/recommend", "/admin", "/resorts", "/resorts/1"})
	void mustStayGatedPathsRedirectAnonymousToLogin(String path) throws Exception {
		// Permit-list lock: if a future permitAll() edit widens the public set to
		// include one of these high-value samples (a real route today, /error, plus
		// representative future prefixes), this assertion fails in CI.
		mockMvc.perform(get(path))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	@WithMockUser
	void errorRouteIsReachableForAuthenticatedUserPastSecurityFilter() throws Exception {
		// Closes F13: /error is a REAL gated route (not the synthetic /whatever). An
		// authenticated request passes the security filter and reaches
		// BasicErrorController, so it is NOT redirected to /login. The exact status of
		// a direct /error hit is an impl detail, so we only assert "not bounced".
		mockMvc.perform(get("/error"))
				.andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
						.as("authenticated /error must reach the handler past security, not redirect to /login")
						.isNotEqualTo("/login"));
	}

	@Test
	@WithMockUser(roles = "USER")
	void userAdminResortsReturns403() throws Exception {
		mockMvc.perform(get("/admin/resorts"))
				.andExpect(status().isForbidden());
	}

	@Test
	void unknownPathStaysGatedForAnonymous() throws Exception {
		// Canary carried over from RouteGatingTests: any unmapped, non-permit-listed
		// path must still gate anonymous users via the anyRequest().authenticated() rule.
		mockMvc.perform(get("/whatever"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}
}
