package com.nextslope;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nextslope.config.SecurityConfig;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.UserRegistrationService;
import com.nextslope.user.UserRepository;
import com.nextslope.web.HomeController;

@WebMvcTest(controllers = HomeController.class)
@Import({SecurityConfig.class, AppUserDetailsService.class})
class HomeControllerWebMvcTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private UserRegistrationService userRegistrationService;

	@Test
	@WithMockUser
	void authenticatedLandingPageLinksToResortBrowse() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(view().name("index"))
				.andExpect(content().string(containsString("/resorts")))
				.andExpect(content().string(containsString("Browse resorts")));
	}
}
