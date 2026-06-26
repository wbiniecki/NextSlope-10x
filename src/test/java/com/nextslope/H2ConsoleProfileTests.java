package com.nextslope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
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
 * Guards against the H2 console ever becoming a production exposure.
 *
 * <p>The dedicated H2 {@code SecurityFilterChain} is {@code @Profile("!prod")}, so it
 * must be permit-all everywhere except prod and absent in prod (where
 * {@code /h2-console/**} then falls through to the main chain's
 * {@code authenticated()} rule). Each profile is exercised in its own web-slice
 * context — the web slice never wires the Neon datasource/Flyway, so the prod case
 * stays light and does not need prod secrets.
 */
class H2ConsoleProfileTests {

	@Nested
	@WebMvcTest
	@Import({SecurityConfig.class, AppUserDetailsService.class})
	class NonProdProfile {

		@Autowired
		private MockMvc mockMvc;

		@MockitoBean
		private UserRepository userRepository;

		@MockitoBean
		private UserRegistrationService userRegistrationService;

		@MockitoBean
		private com.nextslope.resort.ResortRepository resortRepository;

		@MockitoBean
		private PreferenceProfileService preferenceProfileService;

		@MockitoBean
		private VisitedResortService visitedResortService;

		@MockitoBean
		private CurrentUserService currentUserService;

		@Test
		void h2ConsoleIsPublicUnderNonProdProfile() throws Exception {
			mockMvc.perform(get("/h2-console/"))
					.andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
							.as("/h2-console/** must be permit-all under a non-prod profile")
							.isNotEqualTo("/login"));
		}
	}

	@Nested
	@WebMvcTest
	@Import({SecurityConfig.class, AppUserDetailsService.class})
	@ActiveProfiles("prod")
	class ProdProfile {

		@Autowired
		private MockMvc mockMvc;

		@MockitoBean
		private UserRepository userRepository;

		@MockitoBean
		private UserRegistrationService userRegistrationService;

		@MockitoBean
		private com.nextslope.resort.ResortRepository resortRepository;

		@MockitoBean
		private PreferenceProfileService preferenceProfileService;

		@MockitoBean
		private VisitedResortService visitedResortService;

		@MockitoBean
		private CurrentUserService currentUserService;

		@Test
		void h2ConsoleChainAbsentUnderProdProfileFallsThroughToAuthenticated() throws Exception {
			mockMvc.perform(get("/h2-console/"))
					.andExpect(status().is3xxRedirection())
					.andExpect(redirectedUrl("/login"));
		}
	}
}
