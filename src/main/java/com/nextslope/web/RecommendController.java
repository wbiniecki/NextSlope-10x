package com.nextslope.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;

import com.nextslope.recommendation.RecommendationResult;
import com.nextslope.recommendation.RecommendationService;
import com.nextslope.user.CurrentUserService;

import lombok.RequiredArgsConstructor;

/**
 * Authenticated, principal-scoped recommendation entry point. The recommendation is always computed
 * for the current user — there is no user id in the path, so there is no cross-user (IDOR) surface.
 * Returns the discriminated result fragment for HTMX to swap into the results container in place,
 * mirroring {@link VisitedController}.
 */
@Controller
@RequiredArgsConstructor
public class RecommendController {

	private final RecommendationService recommendationService;
	private final CurrentUserService currentUserService;

	@PostMapping("/recommend")
	public String recommend(@AuthenticationPrincipal UserDetails principal, Model model) {
		Long userId = currentUserService.requireUserId(principal);

		RecommendationResult result = recommendationService.recommend(userId);

		// Attribute name must match the fragment parameter name so the returned fragment binds it.
		model.addAttribute("result", result);
		return "resorts/recommend-results";
	}
}
