package com.nextslope.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.nextslope.user.User;

/**
 * Demonstrates the two-distinct-persisted-user shape an IDOR test will use, so
 * S-02 / S-04 add a single assertion against a real owned resource rather than
 * re-deriving the fixture + login plumbing.
 *
 * <p>Extends {@link TwoUserIntegrationTestBase}: user A, user B, and the admin are
 * persisted and isolated per test by the base. Here we only prove the identities
 * are real and distinct (two separate authenticated principals) and that the admin
 * fixture carries {@code ROLE_ADMIN}.
 *
 * <p><b>Where the real wrong-owner assertion slots in:</b> no user-owned route
 * exists today, so a genuine "user B requests user A's resource → denied" cannot be
 * asserted yet. When S-02 (profile) / S-04 (visited) introduce an owned route, the
 * slice logs in as user B via {@link #loginAsUserB()}, requests user A's resource,
 * and asserts {@link AccessControlAssertions#assertWrongOwnerDenied} — the seam this
 * test already exercises the fixtures for.
 */
class OwnershipPatternIntegrationTests extends TwoUserIntegrationTestBase {

	@Test
	void userAAndUserBAuthenticateAsDistinctPrincipals() throws Exception {
		MockHttpSession sessionA = loginAsUserA();
		MockHttpSession sessionB = loginAsUserB();

		assertThat(authenticatedUsername(sessionA)).isEqualTo(UserFixtures.USER_A_EMAIL);
		assertThat(authenticatedUsername(sessionB)).isEqualTo(UserFixtures.USER_B_EMAIL);
		assertThat(authenticatedUsername(sessionA))
				.as("user A and user B must resolve to two distinct authenticated identities")
				.isNotEqualTo(authenticatedUsername(sessionB));
	}

	@Test
	void adminFixtureCarriesAdminRole() {
		User admin = userRepository.findByEmail(UserFixtures.ADMIN_EMAIL)
				.orElseThrow(() -> new AssertionError("admin fixture was not persisted"));

		assertThat(admin.getRole()).isEqualTo(User.Role.ADMIN);
	}

	private static String authenticatedUsername(MockHttpSession session) {
		SecurityContext context = (SecurityContext) session
				.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		assertThat(context)
				.as("login should have stored a SecurityContext in the session")
				.isNotNull();
		return context.getAuthentication().getName();
	}
}
