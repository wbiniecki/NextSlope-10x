package com.nextslope.visited;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;

@ExtendWith(MockitoExtension.class)
class VisitedResortServiceTests {

	@Mock
	private VisitedResortRepository visitedResortRepository;

	@Mock
	private ResortRepository resortRepository;

	@InjectMocks
	private VisitedResortService service;

	@Test
	void togglingAnUnvisitedActiveResortMarksItAndReturnsVisited() {
		when(visitedResortRepository.existsByUserIdAndResortId(1L, 10L)).thenReturn(false);
		when(resortRepository.findByIdAndActiveTrue(10L))
				.thenReturn(Optional.of(Resort.builder().id(10L).active(true).build()));

		boolean visited = service.toggle(1L, 10L);

		assertThat(visited).isTrue();
		verify(visitedResortRepository).save(any(VisitedResort.class));
		verify(visitedResortRepository, never()).deleteByUserIdAndResortId(any(), any());
	}

	@Test
	void togglingAVisitedResortUnmarksItRegardlessOfActiveStateAndReturnsUnvisited() {
		when(visitedResortRepository.existsByUserIdAndResortId(1L, 10L)).thenReturn(true);

		boolean visited = service.toggle(1L, 10L);

		assertThat(visited).isFalse();
		verify(visitedResortRepository).deleteByUserIdAndResortId(1L, 10L);
		verify(visitedResortRepository, never()).save(any());
		// Unmark must not depend on the resort being active/present.
		verify(resortRepository, never()).findByIdAndActiveTrue(any());
	}

	@Test
	void markingAMissingOrInactiveResortSignalsNotFound() {
		when(visitedResortRepository.existsByUserIdAndResortId(1L, 99L)).thenReturn(false);
		when(resortRepository.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.toggle(1L, 99L))
				.isInstanceOf(ResortNotFoundException.class);

		verify(visitedResortRepository, never()).save(any());
	}

	@Test
	void aConcurrentDuplicateMarkIsTreatedAsAlreadyVisited() {
		when(visitedResortRepository.existsByUserIdAndResortId(1L, 10L)).thenReturn(false);
		when(resortRepository.findByIdAndActiveTrue(10L))
				.thenReturn(Optional.of(Resort.builder().id(10L).active(true).build()));
		when(visitedResortRepository.save(any(VisitedResort.class)))
				.thenThrow(new DataIntegrityViolationException("uq_visited_user_resort"));

		boolean visited = service.toggle(1L, 10L);

		assertThat(visited).isTrue();
	}

	@Test
	void visitedResortIdsDelegatesToTheRepository() {
		when(visitedResortRepository.findResortIdsByUserId(1L)).thenReturn(Set.of(10L, 20L));

		assertThat(service.visitedResortIds(1L)).containsExactlyInAnyOrder(10L, 20L);
	}
}
