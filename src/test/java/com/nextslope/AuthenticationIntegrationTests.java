package com.nextslope;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.nextslope.user.User;
import com.nextslope.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

	@BeforeEach
	void cleanUsers() {
		userRepository.deleteAll();
	}

	@Test
	void loginWithPersistedUserAuthenticatesAndLandsOnHome() throws Exception {
		userRepository.save(User.builder()
				.email("skier@example.com")
				.passwordHash(passwordEncoder.encode("secret123"))
				.role(User.Role.USER)
				.build());

		mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
						.user("skier@example.com")
						.password("secret123"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/"))
				.andExpect(authenticated());
	}

	@Test
	void loginWithBadCredentialsRedirectsToLoginWithError() throws Exception {
		mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
						.user("nobody@example.com")
						.password("wrong"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void logoutInvalidatesSessionAndRedirectsToLandingWithLogoutParam() throws Exception {
		userRepository.save(User.builder()
				.email("skier@example.com")
				.passwordHash(passwordEncoder.encode("secret123"))
				.role(User.Role.USER)
				.build());

		MvcResult loginResult = mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
						.user("skier@example.com")
						.password("secret123"))
				.andExpect(status().is3xxRedirection())
				.andReturn();

		MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

		mockMvc.perform(post("/logout")
						.with(csrf())
						.session(session))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/?logout"))
				.andExpect(unauthenticated());

		mockMvc.perform(get("/whatever").session(session))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}
}
