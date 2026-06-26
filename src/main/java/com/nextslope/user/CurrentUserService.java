package com.nextslope.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Resolves the authenticated principal to the persistent user id. The Spring principal carries only
 * the email (see {@link AppUserDetailsService}); controllers that need the id share this one lookup
 * instead of re-inlining {@code findByEmail(...).getId()}.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

	private final UserRepository userRepository;

	/** The persistent id of the authenticated user, or {@code 401} when no matching user row exists. */
	public Long requireUserId(UserDetails principal) {
		User user = userRepository.findByEmail(EmailNormalizer.normalize(principal.getUsername()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		return user.getId();
	}
}
