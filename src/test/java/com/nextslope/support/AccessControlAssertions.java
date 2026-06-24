package com.nextslope.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.web.servlet.ResultActions;

/**
 * The §6.4 access-control assertion vocabulary, named once so later slices read as
 * intent ("anonymous redirected to login", "reached past security", "forbidden")
 * rather than raw matchers. Each helper takes a {@link ResultActions} and returns
 * it for chaining.
 */
public final class AccessControlAssertions {

	private AccessControlAssertions() {
	}

	/** anonymous → redirect to {@code /login} (request was bounced by the security filter). */
	public static ResultActions assertRedirectedToLogin(ResultActions actions) throws Exception {
		return actions
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	/**
	 * authenticated → NOT redirected to {@code /login} (the request passed the
	 * security filter and reached a real handler). Asserts the absence of the login
	 * redirect rather than a specific status, since the reached handler's status is
	 * an implementation detail (see plan Critical Implementation Details for the
	 * {@code /error} case).
	 */
	public static ResultActions assertReachedPastSecurity(ResultActions actions) throws Exception {
		return actions.andExpect(result -> {
			String redirectedUrl = result.getResponse().getRedirectedUrl();
			assertThat(redirectedUrl)
					.as("authenticated request should not be redirected to the login page")
					.isNotEqualTo("/login");
		});
	}

	/** wrong-role / unauthorized → 403 Forbidden. */
	public static ResultActions assertForbidden(ResultActions actions) throws Exception {
		return actions.andExpect(status().isForbidden());
	}

	/**
	 * wrong-owner → denied. SEAM / PLACEHOLDER.
	 *
	 * <p>No user-owned resource or route exists today (the profile/visited entities
	 * arrive with S-02 / S-04), so there is nothing to assert a real cross-user
	 * denial against yet. This placeholder documents the vocabulary slot: when an
	 * owned route lands, the slice specializes this assertion for a "user B requests
	 * user A's resource" request — denied as either {@code 403} (forbidden) or
	 * {@code 404} (existence not leaked) per the PRD privacy guardrail. It currently
	 * delegates to {@link #assertForbidden} so the seam is callable and green.
	 */
	public static ResultActions assertWrongOwnerDenied(ResultActions actions) throws Exception {
		return assertForbidden(actions);
	}
}
