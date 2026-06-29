package com.nextslope.resort;

import java.util.Optional;

/**
 * Test-only extension of {@link ResortRepository}. The seed-resync tests need to look a row up by
 * {@code external_id} to assert in-place upsert / active-preservation behavior, but production code
 * reconciles via a single {@code findAll()} pass — so this finder lives with the tests that use it
 * rather than widening the production repository surface.
 */
public interface ResortTestRepository extends ResortRepository {

	Optional<Resort> findByExternalId(Long externalId);
}
