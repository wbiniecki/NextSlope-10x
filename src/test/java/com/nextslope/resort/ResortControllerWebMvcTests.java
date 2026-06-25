package com.nextslope.resort;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Optional;

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
import com.nextslope.web.ResortController;

@WebMvcTest(controllers = ResortController.class)
@Import({SecurityConfig.class, AppUserDetailsService.class})
class ResortControllerWebMvcTests {

	// Sentinel that must never reach the rendered HTML — external_id is admin-only.
	private static final long EXTERNAL_ID_SENTINEL = 987654321L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ResortRepository resortRepository;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private UserRegistrationService userRegistrationService;

	private static Resort sampleResort() {
		return Resort.builder()
				.id(7L)
				.externalId(EXTERNAL_ID_SENTINEL)
				.name("Sölden")
				.country("Austria")
				.continent("Europe")
				.season("November - May, June - August")
				.price(60)
				.highestPoint(3340)
				.lowestPoint(1350)
				.beginnerSlopes(20)
				.intermediateSlopes(50)
				.difficultSlopes(30)
				.totalSlopes(100)
				.longestRun(15)
				.snowCannons(400)
				.surfaceLifts(5)
				.chairLifts(15)
				.gondolaLifts(10)
				.totalLifts(30)
				.liftCapacity(70000)
				.childFriendly(true)
				.snowparks(true)
				.nightskiing(false)
				.summerSkiing(true)
				.active(true)
				.build();
	}

	@Test
	void anonymousBrowseRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/resorts"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void anonymousDetailRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/resorts/7"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	@WithMockUser
	void authenticatedBrowseRendersListWithFacts() throws Exception {
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(sampleResort()));

		mockMvc.perform(get("/resorts"))
				.andExpect(status().isOk())
				.andExpect(view().name("resorts/list"))
				.andExpect(content().string(containsString("Sölden")))
				.andExpect(content().string(containsString("Austria")));
	}

	@Test
	@WithMockUser
	void browseListDoesNotExposeExternalId() throws Exception {
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(sampleResort()));

		mockMvc.perform(get("/resorts"))
				.andExpect(content().string(not(containsString(String.valueOf(EXTERNAL_ID_SENTINEL)))));
	}

	@Test
	@WithMockUser
	void authenticatedDetailForKnownIdRendersDetail() throws Exception {
		when(resortRepository.findByIdAndActiveTrue(7L)).thenReturn(Optional.of(sampleResort()));

		mockMvc.perform(get("/resorts/7"))
				.andExpect(status().isOk())
				.andExpect(view().name("resorts/detail"))
				.andExpect(content().string(containsString("Sölden")))
				.andExpect(content().string(not(containsString(String.valueOf(EXTERNAL_ID_SENTINEL)))));
	}

	@Test
	@WithMockUser
	void detailForUnknownIdReturns404() throws Exception {
		when(resortRepository.findByIdAndActiveTrue(404L)).thenReturn(Optional.empty());

		mockMvc.perform(get("/resorts/404"))
				.andExpect(status().isNotFound());
	}
}
