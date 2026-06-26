package com.nextslope.profile;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceProfileRepository extends JpaRepository<PreferenceProfile, Long> {

	Optional<PreferenceProfile> findByUserId(Long userId);
}
