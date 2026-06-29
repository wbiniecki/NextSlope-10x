package com.nextslope.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import com.nextslope.recommendation.RecommendationResult;
import com.nextslope.recommendation.RecommendationService;
import com.nextslope.recommendation.ResortCard;
import com.nextslope.resort.DifficultyMix;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.CurrentUserService;
import com.nextslope.user.UserRepository;

/**
 * Controller contract for {@code POST /recommend}: the route is gated, CSRF-guarded, and
 * principal-scoped (no id in the path → no IDOR surface). The handler resolves the current user,
 * calls the recommendation service, and renders the discriminated result fragment for HTMX to swap
 * in place — mirroring {@link VisitedControllerWebMvcTests}.
 */
@WebMvcTest(controllers = RecommendController.class)
@Import({SecurityConfig.class, AppUserDetailsService.class})
class RecommendControllerWebMvcTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RecommendationService recommendationService;

	@MockitoBean
	private CurrentUserService currentUserService;

	@MockitoBean
	private UserRepository userRepository;

	@BeforeEach
	void mockUserExists() {
		when(userRepository.existsByEmail(anyString())).thenReturn(true);
	}

	private static ResortCard card(String name, String country, String rationale) {
		return new ResortCard(1L, name, country, 3000, 80, 20, new DifficultyMix(40, 40, 20), rationale);
	}

	@Test
	void anonymousPostRedirectsToLogin() throws Exception {
		mockMvc.perform(post("/recommend").with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));

		verify(recommendationService, never()).recommend(anyLong());
	}

	@Test
	@WithMockUser(username = "user")
	void recommendationsRenderCardsAndCallServiceWithResolvedUser() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(7L);
		when(recommendationService.recommend(7L)).thenReturn(RecommendationResult.recommendations(
				List.of(card("Chamonix", "France", "a strong fit in your selected region, France"))));

		mockMvc.perform(post("/recommend").with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Chamonix")))
				.andExpect(content().string(containsString("a strong fit in your selected region")));

		verify(recommendationService).recommend(7L);
	}

	@Test
	@WithMockUser(username = "user")
	void postWithoutCsrfIsForbidden() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(7L);

		mockMvc.perform(post("/recommend"))
				.andExpect(status().isForbidden());

		verify(recommendationService, never()).recommend(anyLong());
	}

	@Test
	@WithMockUser(username = "user")
	void sparseStateRendersTheExplanation() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(7L);
		when(recommendationService.recommend(7L)).thenReturn(RecommendationResult.sparse(
				"We found only 1 resort matching your filters, so we can't show three recommendations yet."
						+ " Try widening your selected region to see three picks."));

		mockMvc.perform(post("/recommend").with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Try widening your selected region")));
	}

	@Test
	@WithMockUser(username = "user")
	void noProfileStateRendersTheSetUpPrompt() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(7L);
		when(recommendationService.recommend(7L)).thenReturn(RecommendationResult.noProfile(
				"Set up your preference profile to get three tailored resort picks."));

		mockMvc.perform(post("/recommend").with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Set up your preference profile")));
	}
}
