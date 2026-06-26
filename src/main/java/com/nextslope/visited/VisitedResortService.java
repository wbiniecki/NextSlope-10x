package com.nextslope.visited;

import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextslope.resort.ResortRepository;

import lombok.RequiredArgsConstructor;

/**
 * Owner-scoped mark/unmark/read of visited resorts. Every method takes the authenticated user's id
 * (never an arbitrary mark id) — there is no addressable cross-user route — mirroring the
 * principal-scoped shape of {@code PreferenceProfileService}.
 */
@Service
@RequiredArgsConstructor
public class VisitedResortService {

	private final VisitedResortRepository visitedResortRepository;
	private final ResortRepository resortRepository;

	/**
	 * Flip the visited state for {@code (userId, resortId)}. If a mark exists it is removed (returns
	 * {@code false}); otherwise the resort must be active ({@link ResortNotFoundException} when not) and
	 * a mark is inserted (returns {@code true}). Unmark succeeds regardless of the resort's active state
	 * so a reference to a later-deactivated resort can always be cleared (FR-013).
	 */
	@Transactional
	public boolean toggle(Long userId, Long resortId) {
		if (visitedResortRepository.existsByUserIdAndResortId(userId, resortId)) {
			visitedResortRepository.deleteByUserIdAndResortId(userId, resortId);
			return false;
		}

		resortRepository.findByIdAndActiveTrue(resortId)
				.orElseThrow(() -> new ResortNotFoundException(resortId));

		try {
			visitedResortRepository.save(VisitedResort.builder().userId(userId).resortId(resortId).build());
		} catch (DataIntegrityViolationException ex) {
			// Concurrent double-mark before this insert: the UNIQUE(user_id, resort_id) constraint
			// rejects the second row. The mark exists either way, so this is already-visited.
		}
		return true;
	}

	/** Ids of the resorts the given user has marked visited — the read S-05 reuses. */
	@Transactional(readOnly = true)
	public Set<Long> visitedResortIds(Long userId) {
		return visitedResortRepository.findResortIdsByUserId(userId);
	}
}
