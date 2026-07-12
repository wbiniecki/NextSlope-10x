package com.nextslope.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.nextslope.user.AccountService;
import com.nextslope.user.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Self-service account deletion (S-07). Like {@link ProfileController}, there is no id in the
 * path — the deleted account is always the authenticated principal's, so no cross-user (IDOR)
 * surface exists. Routes stay inside the {@code anyRequest().authenticated()} gate.
 */
@Controller
@RequiredArgsConstructor
public class AccountController {

	private final CurrentUserService currentUserService;
	private final AccountService accountService;
	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	@GetMapping("/account/delete")
	public String confirmDelete(@AuthenticationPrincipal UserDetails principal) {
		currentUserService.requireUserId(principal);
		return "account/confirm-delete";
	}

	@PostMapping("/account/delete")
	public String deleteAccount(@AuthenticationPrincipal UserDetails principal,
			HttpServletRequest request, HttpServletResponse response) {
		Long userId = currentUserService.requireUserId(principal);
		accountService.deleteAccount(userId);
		// The caller's own session must die with the account. A flash attribute would not survive
		// the invalidation, so success is signaled via the query param (mirrors the ?logout banner).
		logoutHandler.logout(request, response,
				SecurityContextHolder.getContext().getAuthentication());
		return "redirect:/?deleted";
	}
}
