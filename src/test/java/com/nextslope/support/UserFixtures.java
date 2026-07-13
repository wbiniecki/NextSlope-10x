package com.nextslope.support;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.nextslope.user.User;

/**
 * Canonical, distinct test identities for access-control / IDOR tests.
 *
 * <p>Two ordinary users (A and B) with separate emails give every ownership /
 * cross-user (IDOR) test two real principals; the admin carries
 * {@link User.Role#ADMIN} for role-gating tests. Centralizing the emails,
 * passwords, and roles here means later slices reuse one identity set instead of
 * each test inlining its own {@code User.builder()}.
 *
 * <p>Password hashing is the caller's concern: the factory methods take a
 * {@link PasswordEncoder} so this class stays a plain static utility, free of any
 * Spring context. The plaintext passwords are exposed as constants for driving
 * {@code formLogin(...)}.
 */
public final class UserFixtures {

	public static final String USER_A_EMAIL = "user-a@example.com";
	public static final String USER_A_PASSWORD = "user-a-secret1";

	public static final String USER_B_EMAIL = "user-b@example.com";
	public static final String USER_B_PASSWORD = "user-b-secret1";

	public static final String USER_C_EMAIL = "user-c@example.com";
	public static final String USER_C_PASSWORD = "user-c-secret1";

	public static final String ADMIN_EMAIL = "admin@example.com";
	public static final String ADMIN_PASSWORD = "admin-secret1";

	private UserFixtures() {
	}

	public static User userA(PasswordEncoder passwordEncoder) {
		return User.builder()
				.email(USER_A_EMAIL)
				.passwordHash(passwordEncoder.encode(USER_A_PASSWORD))
				.role(User.Role.USER)
				.build();
	}

	public static User userB(PasswordEncoder passwordEncoder) {
		return User.builder()
				.email(USER_B_EMAIL)
				.passwordHash(passwordEncoder.encode(USER_B_PASSWORD))
				.role(User.Role.USER)
				.build();
	}

	public static User userC(PasswordEncoder passwordEncoder) {
		return User.builder()
				.email(USER_C_EMAIL)
				.passwordHash(passwordEncoder.encode(USER_C_PASSWORD))
				.role(User.Role.USER)
				.build();
	}

	public static User admin(PasswordEncoder passwordEncoder) {
		return User.builder()
				.email(ADMIN_EMAIL)
				.passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
				.role(User.Role.ADMIN)
				.build();
	}
}
