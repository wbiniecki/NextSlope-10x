package com.nextslope.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.visited.VisitedResortRepository;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates permanent account deletion (S-07). The schema has no DB-level cascade — referential
 * integrity for user-owned rows is owned by the application — so every trace of the user is removed
 * here, children before parents: visited marks (bulk, no FK), then the preference profile as a
 * managed entity (Hibernate removes its {@code preference_profile_regions} element-collection rows
 * first), then the {@code users} row.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

	private final VisitedResortRepository visitedResortRepository;
	private final PreferenceProfileRepository preferenceProfileRepository;
	private final UserRepository userRepository;

	@Transactional
	public void deleteAccount(Long userId) {
		visitedResortRepository.deleteByUserId(userId);
		// Entity delete, never a derived bulk delete: only a managed-entity removal cascades to the
		// regions element collection; a bulk delete would orphan those rows and violate their FK.
		preferenceProfileRepository.findByUserId(userId)
				.ifPresent(preferenceProfileRepository::delete);
		userRepository.deleteById(userId);
	}
}
