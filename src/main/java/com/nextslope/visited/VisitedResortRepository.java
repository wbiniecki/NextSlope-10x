package com.nextslope.visited;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface VisitedResortRepository extends JpaRepository<VisitedResort, Long> {

	boolean existsByUserIdAndResortId(Long userId, Long resortId);

	@Modifying
	@Transactional
	long deleteByUserIdAndResortId(Long userId, Long resortId);

	/** The S-05 read: ids of the resorts a given user has marked visited. */
	@Query("select v.resortId from VisitedResort v where v.userId = :userId")
	Set<Long> findResortIdsByUserId(@Param("userId") Long userId);
}
