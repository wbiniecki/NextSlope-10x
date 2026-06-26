package com.nextslope.config;

import java.io.IOException;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nextslope.user.EmailNormalizer;
import com.nextslope.user.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Invalidates an authenticated session whose principal no longer maps to a persisted user.
 *
 * <p>Such a stale session arises when the backing account is deleted (S-07) or the database is
 * reset in development while the browser keeps a still-valid session cookie. The session carries an
 * authentication, so the home page would claim "signed in" while every user-scoped page fails its
 * {@code findByEmail} lookup with a confusing 401. Rather than leave the user in that dead-end, this
 * filter logs them out: the security context is cleared and the session invalidated, so the home
 * page renders the signed-out view and gated routes cleanly redirect to {@code /login}.
 *
 * <p>Runs before the authorization filter so a cleared context is seen as anonymous for the rest of
 * the request. Anonymous requests are skipped (no DB hit).
 */
@RequiredArgsConstructor
public class StaleAuthenticatedSessionFilter extends OncePerRequestFilter {

	private final UserRepository userRepository;
	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (isAuthenticatedUser(authentication) && !userStillExists(authentication)) {
			logoutHandler.logout(request, response, authentication);
		}
		filterChain.doFilter(request, response);
	}

	private boolean isAuthenticatedUser(Authentication authentication) {
		return authentication != null
				&& authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken);
	}

	private boolean userStillExists(Authentication authentication) {
		return userRepository.existsByEmail(EmailNormalizer.normalize(authentication.getName()));
	}
}
