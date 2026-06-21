package com.nextslope.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.nextslope.user.User;
import com.nextslope.user.UserRepository;

@DataJpaTest
@Import(SecurityConfig.class)
class AdminBootstrapTests {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void cleanUsers() {
		userRepository.deleteAll();
	}

	@Test
	void createsAdminWhenConfigured() {
		AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder,
				"admin@example.com", "adminpass123");
		bootstrap.bootstrap();

		assertThat(userRepository.findAll()).hasSize(1);
		User admin = userRepository.findByEmail("admin@example.com").orElseThrow();
		assertThat(admin.getRole()).isEqualTo(User.Role.ADMIN);
		assertThat(passwordEncoder.matches("adminpass123", admin.getPasswordHash())).isTrue();
	}

	@Test
	void idempotentOnSecondRun() {
		AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder,
				"admin@example.com", "adminpass123");
		bootstrap.bootstrap();
		bootstrap.bootstrap();

		assertThat(userRepository.count()).isEqualTo(1);
	}

	@Test
	void noOpWhenEnvVarsUnset() {
		AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder, "", "");

		assertThatCode(bootstrap::bootstrap).doesNotThrowAnyException();
		assertThat(userRepository.count()).isZero();
	}

	@Test
	void noOpWhenOnlyEmailSet() {
		AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder, "admin@example.com", "");

		assertThatCode(bootstrap::bootstrap).doesNotThrowAnyException();
		assertThat(userRepository.count()).isZero();
	}

	@Test
	void leavesExistingUserUnchanged() {
		userRepository.save(User.builder()
				.email("admin@example.com")
				.passwordHash(passwordEncoder.encode("existing"))
				.role(User.Role.USER)
				.build());

		AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder,
				"admin@example.com", "adminpass123");
		bootstrap.bootstrap();

		assertThat(userRepository.count()).isEqualTo(1);
		User user = userRepository.findByEmail("admin@example.com").orElseThrow();
		assertThat(user.getRole()).isEqualTo(User.Role.USER);
		assertThat(passwordEncoder.matches("existing", user.getPasswordHash())).isTrue();
	}

	@Test
	void normalizesEmailBeforeLookupAndSave() {
		userRepository.save(User.builder()
				.email("alice@x.com")
				.passwordHash(passwordEncoder.encode("existing"))
				.role(User.Role.USER)
				.build());

		AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder,
				"Alice@x.com", "adminpass123");
		bootstrap.bootstrap();

		assertThat(userRepository.count()).isEqualTo(1);
		assertThat(userRepository.findByEmail("alice@x.com").orElseThrow().getRole())
				.isEqualTo(User.Role.USER);
	}
}
