package com.nextslope.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.nextslope.resort.Resort;

@ExtendWith(MockitoExtension.class)
class PreferenceProfileServiceTests {

	@Mock
	private PreferenceProfileRepository preferenceProfileRepository;

	@Mock
	private com.nextslope.resort.ResortRepository resortRepository;

	@InjectMocks
	private PreferenceProfileService service;

	private static Resort resort(String country) {
		return Resort.builder().name(country + " Resort").country(country).active(true).build();
	}

	@Test
	void loadFormForUserReturnsDefaultsWhenNoProfileExists() {
		when(preferenceProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

		PreferenceProfileForm form = service.loadFormForUser(1L);

		assertThat(form.getExperienceLevel()).isEqualTo(ExperienceLevel.INTERMEDIATE);
		assertThat(form.getDifficultyBand()).isEqualTo(DifficultyBand.BALANCED);
		assertThat(form.getNoveltyPreference()).isEqualTo(NoveltyPreference.REVISIT_OKAY);
		assertThat(form.isAnyRegion()).isTrue();
		assertThat(form.getRegionCountries()).isEmpty();
	}

	@Test
	void loadFormForUserDerivesAnyRegionFromEmptySavedRegionSet() {
		PreferenceProfile saved = PreferenceProfile.builder()
				.userId(1L)
				.experienceLevel(ExperienceLevel.ADVANCED)
				.difficultyBand(DifficultyBand.MOSTLY_HARD)
				.noveltyPreference(NoveltyPreference.NEW_ONLY)
				.regionCountries(Set.of())
				.build();
		when(preferenceProfileRepository.findByUserId(1L)).thenReturn(Optional.of(saved));

		PreferenceProfileForm form = service.loadFormForUser(1L);

		assertThat(form.getExperienceLevel()).isEqualTo(ExperienceLevel.ADVANCED);
		assertThat(form.isAnyRegion()).isTrue();
		assertThat(form.getRegionCountries()).isEmpty();
	}

	@Test
	void loadFormForUserMapsSavedRegionSetWithAnyRegionFalse() {
		PreferenceProfile saved = PreferenceProfile.builder()
				.userId(1L)
				.experienceLevel(ExperienceLevel.BEGINNER)
				.difficultyBand(DifficultyBand.MOSTLY_EASY)
				.noveltyPreference(NoveltyPreference.REVISIT_OKAY)
				.regionCountries(Set.of("France", "Austria"))
				.build();
		when(preferenceProfileRepository.findByUserId(1L)).thenReturn(Optional.of(saved));

		PreferenceProfileForm form = service.loadFormForUser(1L);

		assertThat(form.isAnyRegion()).isFalse();
		assertThat(form.getRegionCountries()).containsExactlyInAnyOrder("France", "Austria");
	}

	@Test
	void saveUpsertsExistingProfileInPlace() {
		PreferenceProfile existing = PreferenceProfile.builder()
				.id(7L)
				.userId(1L)
				.experienceLevel(ExperienceLevel.BEGINNER)
				.difficultyBand(DifficultyBand.MOSTLY_EASY)
				.noveltyPreference(NoveltyPreference.REVISIT_OKAY)
				.build();
		when(preferenceProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

		PreferenceProfileForm form = PreferenceProfileForm.defaults();
		form.setExperienceLevel(ExperienceLevel.ADVANCED);
		form.setDifficultyBand(DifficultyBand.MOSTLY_HARD);
		form.setNoveltyPreference(NoveltyPreference.NEW_ONLY);

		service.save(1L, form);

		ArgumentCaptor<PreferenceProfile> captor = ArgumentCaptor.forClass(PreferenceProfile.class);
		verify(preferenceProfileRepository).save(captor.capture());
		PreferenceProfile persisted = captor.getValue();
		assertThat(persisted.getId()).isEqualTo(7L);
		assertThat(persisted.getUserId()).isEqualTo(1L);
		assertThat(persisted.getExperienceLevel()).isEqualTo(ExperienceLevel.ADVANCED);
		assertThat(persisted.getDifficultyBand()).isEqualTo(DifficultyBand.MOSTLY_HARD);
		assertThat(persisted.getNoveltyPreference()).isEqualTo(NoveltyPreference.NEW_ONLY);
	}

	@Test
	void saveNormalizesAnyRegionToEmptyRegionSet() {
		when(preferenceProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

		PreferenceProfileForm form = PreferenceProfileForm.defaults();
		form.setAnyRegion(true);
		form.setRegionCountries(List.of("France"));

		service.save(1L, form);

		ArgumentCaptor<PreferenceProfile> captor = ArgumentCaptor.forClass(PreferenceProfile.class);
		verify(preferenceProfileRepository).save(captor.capture());
		assertThat(captor.getValue().getRegionCountries()).isEmpty();
	}

	@Test
	void savePersistsSelectedCountriesWithinVocabulary() {
		when(preferenceProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(resort("Austria"), resort("France")));

		PreferenceProfileForm form = PreferenceProfileForm.defaults();
		form.setAnyRegion(false);
		form.setRegionCountries(List.of("France"));

		service.save(1L, form);

		ArgumentCaptor<PreferenceProfile> captor = ArgumentCaptor.forClass(PreferenceProfile.class);
		verify(preferenceProfileRepository).save(captor.capture());
		assertThat(captor.getValue().getRegionCountries()).containsExactly("France");
	}

	@Test
	void saveRejectsOutOfVocabularyCountry() {
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(resort("France")));

		PreferenceProfileForm form = PreferenceProfileForm.defaults();
		form.setAnyRegion(false);
		form.setRegionCountries(List.of("Atlantis"));

		assertThatThrownBy(() -> service.save(1L, form))
				.isInstanceOf(UnknownRegionCountryException.class);

		verify(preferenceProfileRepository, never()).save(any());
	}

	@Test
	void availableCountriesAreDistinctAndSorted() {
		when(resortRepository.findByActiveTrueOrderByCountryAscNameAsc())
				.thenReturn(List.of(resort("France"), resort("Austria"), resort("France")));

		List<String> countries = service.availableCountries();

		assertThat(countries).containsExactly("Austria", "France");
	}
}
