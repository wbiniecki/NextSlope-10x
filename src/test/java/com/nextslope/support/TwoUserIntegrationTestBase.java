package com.nextslope.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.nextslope.user.UserRepository;

/**
 * Full-stack base for two-user / IDOR / role integration tests.
 *
 * <p>Boots a real {@code @SpringBootTest} context with {@code MockMvc}, persists
 * the {@link UserFixtures} set (user A, user B, admin — passwords hashed via the
 * real {@link PasswordEncoder}) before each test, and clears the table before and
 * after so tests never bleed identities into one another. Subclasses focus on
 * assertions, not setup.
 *
 * <p>Mirrors the session pattern in {@code AuthenticationIntegrationTests}: log in
 * via {@link SecurityMockMvcRequestBuilders#formLogin} and carry the resulting
 * {@link MockHttpSession} into subsequent requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class TwoUserIntegrationTestBase {

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected UserRepository userRepository;

	@Autowired
	protected PasswordEncoder passwordEncoder;

	@BeforeEach
	void seedFixtureUsers() {
		userRepository.deleteAll();
		userRepository.save(UserFixtures.userA(passwordEncoder));
		userRepository.save(UserFixtures.userB(passwordEncoder));
		userRepository.save(UserFixtures.admin(passwordEncoder));
	}

	@AfterEach
	void clearFixtureUsers() {
		userRepository.deleteAll();
	}

	/**
	 * Logs the given fixture user in and returns the authenticated session to reuse
	 * on later requests.
	 */
	protected MockHttpSession loginAs(String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
						.user(email)
						.password(password))
				.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

	protected MockHttpSession loginAsUserA() throws Exception {
		return loginAs(UserFixtures.USER_A_EMAIL, UserFixtures.USER_A_PASSWORD);
	}

	protected MockHttpSession loginAsUserB() throws Exception {
		return loginAs(UserFixtures.USER_B_EMAIL, UserFixtures.USER_B_PASSWORD);
	}

	protected MockHttpSession loginAsAdmin() throws Exception {
		return loginAs(UserFixtures.ADMIN_EMAIL, UserFixtures.ADMIN_PASSWORD);
	}
}
