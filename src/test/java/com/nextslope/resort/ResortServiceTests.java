package com.nextslope.resort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class ResortServiceTests {

	@Mock
	private ResortRepository resortRepository;

	@InjectMocks
	private ResortService service;

	@Test
	void createSetsActiveTrueAndNormalizesTotalSlopes() {
		ResortForm form = validForm();

		service.create(form);

		ArgumentCaptor<Resort> captor = ArgumentCaptor.forClass(Resort.class);
		verify(resortRepository).save(captor.capture());
		Resort saved = captor.getValue();
		assertThat(saved.getActive()).isTrue();
		assertThat(saved.getTotalSlopes()).isEqualTo(35);
		assertThat(saved.getName()).isEqualTo("Test Resort");
		assertThat(saved.getCountry()).isEqualTo("Austria");
	}

	@Test
	void updatePreservesUnmanagedFieldsWhileChangingManagedOnes() {
		Resort existing = Resort.builder()
				.id(5L)
				.name("Old Name")
				.country("Austria")
				.highestPoint(2000)
				.totalLifts(10)
				.beginnerSlopes(5)
				.intermediateSlopes(10)
				.difficultSlopes(5)
				.totalSlopes(20)
				.price(99)
				.latitude(47.0)
				.longitude(11.0)
				.active(true)
				.build();
		when(resortRepository.findById(5L)).thenReturn(Optional.of(existing));

		ResortForm form = validForm();
		form.setName("New Name");

		service.update(5L, form);

		ArgumentCaptor<Resort> captor = ArgumentCaptor.forClass(Resort.class);
		verify(resortRepository).save(captor.capture());
		Resort saved = captor.getValue();
		assertThat(saved.getName()).isEqualTo("New Name");
		assertThat(saved.getPrice()).isEqualTo(99);
		assertThat(saved.getLatitude()).isEqualTo(47.0);
		assertThat(saved.getLongitude()).isEqualTo(11.0);
		assertThat(saved.getTotalSlopes()).isEqualTo(35);
	}

	@Test
	void loadFormMapsManagedFields() {
		Resort resort = Resort.builder()
				.id(3L)
				.name("Sölden")
				.country("Austria")
				.highestPoint(3340)
				.totalLifts(31)
				.beginnerSlopes(14)
				.intermediateSlopes(29)
				.difficultSlopes(13)
				.externalId(42L)
				.active(true)
				.build();
		when(resortRepository.findById(3L)).thenReturn(Optional.of(resort));

		ResortForm form = service.loadForm(3L);

		assertThat(form.getId()).isEqualTo(3L);
		assertThat(form.getName()).isEqualTo("Sölden");
		assertThat(form.getCountry()).isEqualTo("Austria");
		assertThat(form.getHighestPoint()).isEqualTo(3340);
		assertThat(form.getTotalLifts()).isEqualTo(31);
		assertThat(form.getBeginnerSlopes()).isEqualTo(14);
		assertThat(form.getIntermediateSlopes()).isEqualTo(29);
		assertThat(form.getDifficultSlopes()).isEqualTo(13);
		assertThat(form.getExternalId()).isEqualTo(42L);
	}

	@Test
	void duplicateExternalIdThrowsBeforeSave() {
		ResortForm form = validForm();
		form.setExternalId(100L);
		when(resortRepository.findByExternalId(100L))
				.thenReturn(Optional.of(Resort.builder().id(99L).active(true).build()));

		assertThatThrownBy(() -> service.create(form))
				.isInstanceOf(DuplicateExternalIdException.class);

		verify(resortRepository, never()).save(any());
	}

	@Test
	void sameExternalIdOnUpdateIsAllowed() {
		Resort existing = Resort.builder()
				.id(5L)
				.name("Resort")
				.country("Austria")
				.highestPoint(2000)
				.totalLifts(10)
				.beginnerSlopes(5)
				.intermediateSlopes(10)
				.difficultSlopes(5)
				.externalId(100L)
				.active(true)
				.build();
		when(resortRepository.findById(5L)).thenReturn(Optional.of(existing));
		when(resortRepository.findByExternalId(100L)).thenReturn(Optional.of(existing));

		ResortForm form = validForm();
		form.setExternalId(100L);

		service.update(5L, form);

		verify(resortRepository).save(existing);
	}

	@Test
	void dataIntegrityViolationOnSaveIsRelabeledAsDuplicateExternalId() {
		ResortForm form = validForm();
		form.setExternalId(100L);
		when(resortRepository.findByExternalId(100L)).thenReturn(Optional.empty());
		when(resortRepository.save(any())).thenThrow(new DataIntegrityViolationException(
				"could not execute statement; constraint [UQ_RESORTS_EXTERNAL_ID]"));

		assertThatThrownBy(() -> service.create(form))
				.isInstanceOf(DuplicateExternalIdException.class);
	}

	@Test
	void unrelatedDataIntegrityViolationOnSaveIsNotRelabeled() {
		ResortForm form = validForm();
		form.setExternalId(100L);
		when(resortRepository.findByExternalId(100L)).thenReturn(Optional.empty());
		when(resortRepository.save(any()))
				.thenThrow(new DataIntegrityViolationException("some other constraint violation"));

		assertThatThrownBy(() -> service.create(form))
				.isInstanceOf(DataIntegrityViolationException.class)
				.isNotInstanceOf(DuplicateExternalIdException.class);
	}

	@Test
	void toggleActiveInvertsStateAndReturnsNewValue() {
		Resort active = Resort.builder().id(8L).name("Resort").country("Austria").active(true).build();
		when(resortRepository.findById(8L)).thenReturn(Optional.of(active));

		boolean result = service.toggleActive(8L);

		assertThat(result).isFalse();
		assertThat(active.getActive()).isFalse();
		verify(resortRepository).saveAndFlush(active);
	}

	@Test
	void toggleActiveReactivatesAnInactiveResort() {
		Resort inactive = Resort.builder().id(9L).name("Resort").country("France").active(false).build();
		when(resortRepository.findById(9L)).thenReturn(Optional.of(inactive));

		boolean result = service.toggleActive(9L);

		assertThat(result).isTrue();
		assertThat(inactive.getActive()).isTrue();
		verify(resortRepository).saveAndFlush(inactive);
	}

	@Test
	void toggleActiveConcurrentUpdateThrowsDomainConflict() {
		Resort active = Resort.builder().id(10L).name("Resort").country("Austria").active(true).build();
		when(resortRepository.findById(10L)).thenReturn(Optional.of(active));
		when(resortRepository.saveAndFlush(active))
				.thenThrow(new ObjectOptimisticLockingFailureException(Resort.class, 10L));

		assertThatThrownBy(() -> service.toggleActive(10L))
				.isInstanceOf(ConcurrentResortUpdateException.class);
	}

	@Test
	void toggleActiveMissingResortThrows() {
		when(resortRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.toggleActive(99L))
				.isInstanceOf(ResortNotFoundException.class);

		verify(resortRepository, never()).saveAndFlush(any());
	}

	private static ResortForm validForm() {
		ResortForm form = new ResortForm();
		form.setName("Test Resort");
		form.setCountry("Austria");
		form.setHighestPoint(3000);
		form.setTotalLifts(20);
		form.setBeginnerSlopes(10);
		form.setIntermediateSlopes(20);
		form.setDifficultSlopes(5);
		return form;
	}
}
