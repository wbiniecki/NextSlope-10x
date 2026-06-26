package com.nextslope;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.nextslope.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class SignupIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@BeforeEach
	void cleanUsers() {
		userRepository.deleteAll();
	}

	@Test
	void postSignupWithValidInputRedirectsToProfileWithAuthenticatedSession() throws Exception {
		mockMvc.perform(post("/signup")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("email", "newskier@example.com")
						.param("password", "secret123"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/profile"))
				.andExpect(authenticated());
	}
}
