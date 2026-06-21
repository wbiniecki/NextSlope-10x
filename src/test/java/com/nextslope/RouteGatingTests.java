package com.nextslope;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;

import com.nextslope.config.SecurityConfig;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.UserRegistrationService;
import com.nextslope.user.UserRepository;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import({SecurityConfig.class, AppUserDetailsService.class})
class RouteGatingTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private UserRegistrationService userRegistrationService;

	@Test
	void anonymousRequestToProtectedPathRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/whatever"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	@WithMockUser
	void authenticatedRequestToProtectedPathIsAllowed() throws Exception {
		mockMvc.perform(get("/whatever"))
				.andExpect(status().isNotFound());
	}
}
