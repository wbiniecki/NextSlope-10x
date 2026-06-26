package com.nextslope.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import com.nextslope.user.CurrentUserService;
import com.nextslope.visited.ResortNotFoundException;
import com.nextslope.visited.VisitedResortService;

import lombok.RequiredArgsConstructor;

/**
 * Authenticated, principal-scoped toggle of a resort's visited state. There is no id in the path
 * other than the resort being toggled — the mark is always the current user's — so there is no
 * cross-user (IDOR) surface. Returns the updated control fragment for HTMX to swap in place.
 */
@Controller
@RequiredArgsConstructor
public class VisitedController {

	private final VisitedResortService visitedResortService;
	private final CurrentUserService currentUserService;

	@PostMapping("/resorts/{id}/visited")
	public String toggle(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id, Model model) {
		Long userId = currentUserService.requireUserId(principal);

		boolean visited;
		try {
			visited = visitedResortService.toggle(userId, id);
		} catch (ResortNotFoundException ex) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}

		// Attribute names must match the fragment parameter names so the returned fragments bind them.
		model.addAttribute("resortId", id);
		model.addAttribute("visited", visited);
		return "resorts/visited-toggle-response";
	}
}
