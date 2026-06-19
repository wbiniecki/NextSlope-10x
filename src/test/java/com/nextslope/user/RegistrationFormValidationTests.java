package com.nextslope.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class RegistrationFormValidationTests {

	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@Test
	void rejectsMalformedEmailWithMultipleAtSigns() {
		RegistrationForm form = new RegistrationForm();
		form.setEmail("!!!!!!!!@££££@gmail.com");
		form.setPassword("secret123");

		Set<ConstraintViolation<RegistrationForm>> violations = validator.validate(form);

		assertThat(violations).isNotEmpty();
	}
}
