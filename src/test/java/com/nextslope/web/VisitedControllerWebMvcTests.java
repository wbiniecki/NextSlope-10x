package com.nextslope.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nextslope.config.SecurityConfig;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.CurrentUserService;
import com.nextslope.user.UserRepository;
import com.nextslope.visited.ResortNotFoundException;
import com.nextslope.visited.VisitedResortService;

@WebMvcTest(controllers = VisitedController.class)
@Import({SecurityConfig.class, AppUserDetailsService.class})
class VisitedControllerWebMvcTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private VisitedResortService visitedResortService;

	@MockitoBean
	private CurrentUserService currentUserService;

	@MockitoBean
	private UserRepository userRepository;

	@BeforeEach
	void mockUserExists() {
		when(userRepository.existsByEmail(anyString())).thenReturn(true);
	}

	@Test
	void anonymousPostRedirectsToLogin() throws Exception {
		mockMvc.perform(post("/resorts/10/visited").with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));

		verify(visitedResortService, never()).toggle(any(), any());
	}

	@Test
	@WithMockUser(username = "user")
	void markingReturnsTheToggleFragmentAndCallsTheService() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(7L);
		when(visitedResortService.toggle(7L, 10L)).thenReturn(true);

		mockMvc.perform(post("/resorts/10/visited").with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Visited")));

		verify(visitedResortService).toggle(7L, 10L);
	}

	@Test
	@WithMockUser(username = "user")
	void postWithoutCsrfIsForbidden() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(7L);

		mockMvc.perform(post("/resorts/10/visited"))
				.andExpect(status().isForbidden());

		verify(visitedResortService, never()).toggle(any(), any());
	}

	@Test
	@WithMockUser(username = "user")
	void markingAnUnknownResortReturns404() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(7L);
		when(visitedResortService.toggle(7L, 99L)).thenThrow(new ResortNotFoundException(99L));

		mockMvc.perform(post("/resorts/99/visited").with(csrf()))
				.andExpect(status().isNotFound());
	}
}
