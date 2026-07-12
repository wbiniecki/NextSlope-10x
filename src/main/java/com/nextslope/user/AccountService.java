package com.nextslope.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.visited.VisitedResortRepository;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates permanent account deletion (S-07). The schema has no DB-level cascade — referential
 * integrity for user-owned rows is owned by the application — so every trace of the user is removed
 * here, children before parents: visited marks (no FK), then the preference profile as a managed
 * entity (Hibernate removes its {@code preference_profile_regions} element-collection rows first),
 * then the {@code users} row.
 *
 * <p>Accepted MVP tradeoff (impl-review phase 1, F1): a visited-mark write racing this transaction
 * can commit one orphaned {@code visited_resorts} row after the user is gone. Orphans are
 * unreachable — every read is principal-scoped and the principal no longer resolves.
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
		// Managed-entity removal so Hibernate cascades to the regions element collection; a JPQL
		// bulk delete (@Query) would bypass the persistence context and orphan those rows.
		preferenceProfileRepository.findByUserId(userId)
				.ifPresent(preferenceProfileRepository::delete);
		userRepository.deleteById(userId);
	}
}
