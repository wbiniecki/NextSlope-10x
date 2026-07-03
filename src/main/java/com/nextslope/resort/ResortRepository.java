package com.nextslope.resort;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResortRepository extends JpaRepository<Resort, Long> {

	List<Resort> findByActiveTrueOrderByCountryAscNameAsc();

	List<Resort> findAllByOrderByCountryAscNameAsc();

	Optional<Resort> findByIdAndActiveTrue(Long id);

	Optional<Resort> findByExternalId(Long externalId);
}
