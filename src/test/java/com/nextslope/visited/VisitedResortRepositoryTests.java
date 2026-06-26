package com.nextslope.visited;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class VisitedResortRepositoryTests {

	@Autowired
	private VisitedResortRepository visitedResortRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void marksAreFoundByUserAndResort() {
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(1L).resortId(10L).build());

		assertThat(visitedResortRepository.existsByUserIdAndResortId(1L, 10L)).isTrue();
		assertThat(visitedResortRepository.existsByUserIdAndResortId(1L, 99L)).isFalse();
		assertThat(visitedResortRepository.existsByUserIdAndResortId(2L, 10L)).isFalse();
	}

	@Test
	void createdAtIsPopulatedByHibernate() {
		VisitedResort saved = visitedResortRepository.saveAndFlush(
				VisitedResort.builder().userId(1L).resortId(10L).build());

		entityManager.clear();

		VisitedResort found = visitedResortRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getCreatedAt()).isNotNull();
	}

	@Test
	void uniqueConstraintRejectsDuplicateUserResort() {
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(1L).resortId(10L).build());

		assertThatThrownBy(() -> visitedResortRepository.saveAndFlush(
				VisitedResort.builder().userId(1L).resortId(10L).build()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deleteByUserAndResortRemovesMarkAndIsNoOpWhenAbsent() {
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(1L).resortId(10L).build());

		assertThat(visitedResortRepository.deleteByUserIdAndResortId(1L, 10L)).isEqualTo(1L);
		assertThat(visitedResortRepository.existsByUserIdAndResortId(1L, 10L)).isFalse();

		assertThat(visitedResortRepository.deleteByUserIdAndResortId(1L, 10L)).isZero();
	}

	@Test
	void findResortIdsByUserReturnsOnlyThatUsersMarks() {
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(1L).resortId(10L).build());
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(1L).resortId(20L).build());
		visitedResortRepository.saveAndFlush(VisitedResort.builder().userId(2L).resortId(30L).build());

		Set<Long> userOne = visitedResortRepository.findResortIdsByUserId(1L);
		Set<Long> userTwo = visitedResortRepository.findResortIdsByUserId(2L);

		assertThat(userOne).containsExactlyInAnyOrder(10L, 20L);
		assertThat(userTwo).containsExactly(30L);
		assertThat(visitedResortRepository.findResortIdsByUserId(3L)).isEmpty();
	}
}
