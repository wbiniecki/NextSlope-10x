package com.nextslope.support;

import static com.nextslope.support.AccessControlAssertions.assertForbidden;
import static com.nextslope.support.AccessControlAssertions.assertReachedPastSecurity;
import static com.nextslope.support.AccessControlAssertions.assertRedirectedToLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.UserRegistrationService;
import com.nextslope.user.UserRepository;

/**
 * Risk #5 admin-authz vocabulary, proven green against a TEST-ONLY role-gated route.
 *
 * <p>This is the executable template S-06 copies: anonymous → redirect to
 * {@code /login}, authenticated {@code USER} → {@code 403}, {@code ADMIN} →
 * {@code 200}. The route ({@value #ADMIN_ONLY_PATH}) is a pattern fixture that never
 * ships in {@code src/main}.
 *
 * <p><b>Why method security:</b> production {@code SecurityConfig} is binary
 * (permit-listed vs. {@code authenticated()}), so importing it alone would let an
 * authenticated {@code USER} through — there would be no {@code USER → 403}. The
 * role distinction is therefore added test-locally via a {@code @TestConfiguration}
 * that enables method security and a demo handler annotated
 * {@code @PreAuthorize("hasRole('ADMIN')")}. Production {@code SecurityConfig} still
 * supplies the real anonymous-redirect and authenticated-request flow.
 *
 * <p><b>Note for S-06:</b> if admin is ultimately gated via URL authorization
 * ({@code requestMatchers(...).hasRole("ADMIN")}) instead of method security, the
 * assertion vocabulary is identical — only the enforcement seam differs. Call out
 * the chosen seam when S-06 lands.
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

	@Test
	void anonymousIsRedirectedToLogin() throws Exception {
		assertRedirectedToLogin(mockMvc.perform(get(ADMIN_ONLY_PATH)));
	}

	@Test
	@WithMockUser(roles = "USER")
	void authenticatedNonAdminIsForbidden() throws Exception {
		// Authenticated, so it passes production's anyRequest().authenticated(), but
		// @PreAuthorize("hasRole('ADMIN')") denies the USER → 403. This is the case
		// production's binary chain alone could not produce.
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
	 * source set; the role rule is intentionally NOT added to production
	 * {@code SecurityConfig}.
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
