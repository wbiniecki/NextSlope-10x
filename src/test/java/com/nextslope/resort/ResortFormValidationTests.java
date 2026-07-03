package com.nextslope.resort;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class ResortFormValidationTests {

	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@Test
	void blankNameAndCountryProduceFieldErrors() {
		ResortForm form = validForm();
		form.setName("");
		form.setCountry("  ");

		Set<String> invalidFields = validator.validate(form).stream()
				.map(v -> v.getPropertyPath().toString())
				.collect(Collectors.toSet());

		assertThat(invalidFields).contains("name", "country");
	}

	@Test
	void nullIntegerFieldsProduceFieldErrors() {
		ResortForm form = validForm();
		form.setHighestPoint(null);
		form.setTotalLifts(null);
		form.setBeginnerSlopes(null);
		form.setIntermediateSlopes(null);
		form.setDifficultSlopes(null);

		Set<String> invalidFields = validator.validate(form).stream()
				.map(v -> v.getPropertyPath().toString())
				.collect(Collectors.toSet());

		assertThat(invalidFields).containsExactlyInAnyOrder(
				"highestPoint", "totalLifts", "beginnerSlopes", "intermediateSlopes", "difficultSlopes");
	}

	@Test
	void negativeIntegerFieldsProduceFieldErrors() {
		ResortForm form = validForm();
		form.setHighestPoint(-1);
		form.setTotalLifts(-1);
		form.setBeginnerSlopes(-1);
		form.setIntermediateSlopes(-1);
		form.setDifficultSlopes(-1);

		Set<String> invalidFields = validator.validate(form).stream()
				.map(v -> v.getPropertyPath().toString())
				.collect(Collectors.toSet());

		assertThat(invalidFields).containsExactlyInAnyOrder(
				"highestPoint", "totalLifts", "beginnerSlopes", "intermediateSlopes", "difficultSlopes");
	}

	@Test
	void fullyValidFormProducesNoViolations() {
		ResortForm form = validForm();

		Set<ConstraintViolation<ResortForm>> violations = validator.validate(form);

		assertThat(violations).isEmpty();
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
