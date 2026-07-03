package com.nextslope.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.nextslope.user.UserRepository;

/**
 * Guards against the known-credentials dev admin ever being wired in production.
 *
 * <p>{@link DevAdminBootstrap} is {@code @Profile("!prod")}, so it must be present under
 * local/test profiles and absent when the prod profile is active. Uses a minimal context
 * runner so the prod case does not pull Neon/Flyway wiring or Render secrets.
 */
class DevAdminBootstrapProfileTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(DevAdminBootstrapTestSupport.class);

	@Test
	void devAdminBootstrapIsPresentUnderNonProdProfile() {
		contextRunner.run(context -> assertThat(context).hasSingleBean(DevAdminBootstrap.class));
	}

	@Test
	void devAdminBootstrapIsAbsentUnderProdProfile() {
		contextRunner.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).doesNotHaveBean(DevAdminBootstrap.class));
	}

	@Configuration
	@Import(DevAdminBootstrap.class)
	static class DevAdminBootstrapTestSupport {

		@Bean
		PasswordEncoder passwordEncoder() {
			return new BCryptPasswordEncoder();
		}

		@Bean
		UserRepository userRepository() {
			return mock(UserRepository.class);
		}
	}
}
