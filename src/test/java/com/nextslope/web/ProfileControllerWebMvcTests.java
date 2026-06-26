package com.nextslope.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nextslope.config.SecurityConfig;
import com.nextslope.profile.PreferenceProfileForm;
import com.nextslope.profile.PreferenceProfileService;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.CurrentUserService;
import com.nextslope.user.UserRepository;

@WebMvcTest(controllers = ProfileController.class)
@Import({SecurityConfig.class, AppUserDetailsService.class})
class ProfileControllerWebMvcTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PreferenceProfileService preferenceProfileService;

	@MockitoBean
	private CurrentUserService currentUserService;

	@MockitoBean
	private UserRepository userRepository;

	@Test
	void anonymousGetRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/profile"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void anonymousPostRedirectsToLogin() throws Exception {
		mockMvc.perform(post("/profile").with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	@WithMockUser(username = "user")
	void authenticatedGetReturnsFormWithModelAttributes() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(1L);
		when(preferenceProfileService.loadFormForUser(1L)).thenReturn(PreferenceProfileForm.defaults());
		when(preferenceProfileService.availableCountries()).thenReturn(List.of("Austria", "France"));

		mockMvc.perform(get("/profile"))
				.andExpect(status().isOk())
				.andExpect(view().name("profile/form"))
				.andExpect(model().attributeExists("profileForm"))
				.andExpect(model().attribute("availableCountries", List.of("Austria", "France")));
	}

	@Test
	@WithMockUser(username = "user")
	void postWithMissingRequiredAxisReRendersFormWithErrors() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(1L);
		when(preferenceProfileService.availableCountries()).thenReturn(List.of("Austria", "France"));

		mockMvc.perform(post("/profile")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("difficultyBand", "BALANCED")
						.param("noveltyPreference", "REVISIT_OKAY")
						.param("anyRegion", "true"))
				.andExpect(status().isOk())
				.andExpect(view().name("profile/form"))
				.andExpect(model().attributeHasFieldErrors("profileForm", "experienceLevel"))
				.andExpect(model().attributeExists("availableCountries"));

		verify(preferenceProfileService, never()).save(any(), any());
	}

	@Test
	@WithMockUser(username = "user")
	void validPostSavesAndRedirectsToResorts() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(1L);

		mockMvc.perform(post("/profile")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("experienceLevel", "ADVANCED")
						.param("difficultyBand", "BALANCED")
						.param("noveltyPreference", "REVISIT_OKAY")
						.param("anyRegion", "true"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/resorts"));

		verify(preferenceProfileService).save(eq(1L), any(PreferenceProfileForm.class));
	}
}
