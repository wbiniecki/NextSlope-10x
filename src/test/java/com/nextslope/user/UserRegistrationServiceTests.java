package com.nextslope.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.nextslope.config.SecurityConfig;

@DataJpaTest
@Import({UserRegistrationService.class, SecurityConfig.class})
class UserRegistrationServiceTests {

	@Autowired
	private UserRegistrationService userRegistrationService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void cleanUsers() {
		userRepository.deleteAll();
	}

	@Test
	void registerEncodesPasswordAndAssignsUserRole() {
		User saved = userRegistrationService.register("skier@example.com", "secret123");

		assertThat(saved.getEmail()).isEqualTo("skier@example.com");
		assertThat(saved.getRole()).isEqualTo(User.Role.USER);
		assertThat(saved.getPasswordHash()).isNotEqualTo("secret123");
		assertThat(passwordEncoder.matches("secret123", saved.getPasswordHash())).isTrue();
	}

	@Test
	void duplicateEmailRaisesEmailAlreadyExistsException() {
		userRegistrationService.register("skier@example.com", "secret123");

		assertThatThrownBy(() -> userRegistrationService.register("skier@example.com", "otherpass"))
				.isInstanceOf(EmailAlreadyExistsException.class);
	}

	@Test
	void caseVariantDuplicateRaisesEmailAlreadyExistsException() {
		userRegistrationService.register("alice@x.com", "secret123");

		assertThatThrownBy(() -> userRegistrationService.register("Alice@x.com", "otherpass"))
				.isInstanceOf(EmailAlreadyExistsException.class);

		assertThat(userRepository.count()).isEqualTo(1);
	}
}
