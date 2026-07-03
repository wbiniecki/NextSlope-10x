package com.nextslope.support;

import static com.nextslope.support.AccessControlAssertions.assertForbidden;
import static com.nextslope.support.AccessControlAssertions.assertReachedPastSecurity;
import static com.nextslope.support.AccessControlAssertions.assertRedirectedToLogin;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.nextslope.config.SecurityConfig;
import com.nextslope.profile.PreferenceProfileService;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.CurrentUserService;
import com.nextslope.user.UserRegistrationService;
import com.nextslope.user.UserRepository;
import com.nextslope.visited.VisitedResortService;

/**
 * Risk #5 admin-authz vocabulary, proven green against a TEST-ONLY role-gated route.
 *
 * <p>This is the executable template S-06 copies: anonymous → redirect to
 * {@code /login}, authenticated {@code USER} → {@code 403}, {@code ADMIN} →
 * {@code 200}. The route ({@value #ADMIN_ONLY_PATH}) is a pattern fixture that never
 * ships in {@code src/main}.
 *
 * <p><b>Why method security in this fixture:</b> production {@code SecurityConfig} now
 * gates {@code /admin/**} with {@code hasRole("ADMIN")} at the URL layer (S-06), so
 * importing it alone would produce {@code USER → 403} for admin paths. This test still
 * uses a {@code @TestConfiguration} with {@code @PreAuthorize("hasRole('ADMIN')")} on a
 * demo handler ({@value #ADMIN_ONLY_PATH}) so the assertion vocabulary is proven against
 * method security — the template for slices that opt into {@code @PreAuthorize} instead
 * of (or in addition to) URL authorization. Production {@code SecurityConfig} supplies
 * the real anonymous-redirect, authenticated-request, and URL-level admin gate.
 *
 * <p><b>S-06 seam:</b> admin resort management uses URL authorization on
 * {@code /admin/**}. The USER→403 / ADMIN→200 assertions are identical; only the
 * enforcement seam differs from this fixture.
 */
@WebMvcTest
@Import({SecurityConfig.class, AppUserDetailsService.class,
		RoleGatingPatternTests.RoleGatedDemoConfig.class})
class RoleGatingPatternTests {

	static final String ADMIN_ONLY_PATH = "/pattern/admin-only";

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

	@BeforeEach
	void mockUserExists() {
		when(userRepository.existsByEmail(anyString())).thenReturn(true);
	}

	@Test
	void anonymousIsRedirectedToLogin() throws Exception {
		assertRedirectedToLogin(mockMvc.perform(get(ADMIN_ONLY_PATH)));
	}

	@Test
	@WithMockUser(roles = "USER")
	void authenticatedNonAdminIsForbidden() throws Exception {
		// Authenticated, so it passes production's anyRequest().authenticated(), but
		// @PreAuthorize("hasRole('ADMIN')") denies the USER → 403. URL-level admin
		// gating in SecurityConfig would also deny USER; this fixture proves the
		// method-security seam explicitly.
		assertForbidden(mockMvc.perform(get(ADMIN_ONLY_PATH)));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminReachesTheRoute() throws Exception {
		assertReachedPastSecurity(mockMvc.perform(get(ADMIN_ONLY_PATH)))
				.andExpect(status().isOk())
				.andExpect(content().string("admin-only-ok"));
	}

	/**
	 * Test-scoped method-security wiring + demo controller. Lives only in the test
	 * source set; production {@code /admin/**} is gated via URL authorization in
	 * {@code SecurityConfig}, while this fixture demonstrates {@code @PreAuthorize}.
	 */
	@TestConfiguration
	@EnableMethodSecurity
	static class RoleGatedDemoConfig {

		@Bean
		RoleGatedDemoController roleGatedDemoController() {
			return new RoleGatedDemoController();
		}
	}

	@Controller
	static class RoleGatedDemoController {

		@GetMapping(ADMIN_ONLY_PATH)
		@ResponseBody
		@PreAuthorize("hasRole('ADMIN')")
		String adminOnly() {
			return "admin-only-ok";
		}
	}
}
