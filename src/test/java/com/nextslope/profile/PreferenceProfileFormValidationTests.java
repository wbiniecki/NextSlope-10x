package com.nextslope.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class PreferenceProfileFormValidationTests {

	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@Test
	void missingAxesProduceFieldErrors() {
		PreferenceProfileForm form = new PreferenceProfileForm();

		Set<ConstraintViolation<PreferenceProfileForm>> violations = validator.validate(form);

		Set<String> invalidFields = violations.stream()
				.map(v -> v.getPropertyPath().toString())
				.collect(Collectors.toSet());
		assertThat(invalidFields)
				.contains("experienceLevel", "difficultyBand", "noveltyPreference");
	}

	@Test
	void defaultsFactoryProducesAValidForm() {
		PreferenceProfileForm form = PreferenceProfileForm.defaults();

		Set<ConstraintViolation<PreferenceProfileForm>> violations = validator.validate(form);

		assertThat(violations).isEmpty();
		assertThat(form.getExperienceLevel()).isEqualTo(ExperienceLevel.INTERMEDIATE);
		assertThat(form.getDifficultyBand()).isEqualTo(DifficultyBand.BALANCED);
		assertThat(form.getNoveltyPreference()).isEqualTo(NoveltyPreference.REVISIT_OKAY);
		assertThat(form.isAnyRegion()).isTrue();
		assertThat(form.getRegionCountries()).isEqualTo(List.of());
	}
}
