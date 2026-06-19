package com.nextslope.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.nextslope.user.EmailNormalizer;
import com.nextslope.user.User;
import com.nextslope.user.UserRepository;

@Component
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final String adminEmail;
	private final String adminPassword;

	public AdminBootstrap(
			UserRepository userRepository,
			PasswordEncoder passwordEncoder,
			@Value("${ADMIN_EMAIL:}") String adminEmail,
			@Value("${ADMIN_PASSWORD:}") String adminPassword) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.adminEmail = adminEmail;
		this.adminPassword = adminPassword;
	}

	@Override
	public void run(ApplicationArguments args) {
		bootstrap();
	}

	void bootstrap() {
		if (adminEmail.isBlank() || adminPassword.isBlank()) {
			log.info("admin bootstrap skipped — ADMIN_EMAIL/ADMIN_PASSWORD not set");
			return;
		}

		String normalizedEmail = EmailNormalizer.normalize(adminEmail);
		if (userRepository.findByEmail(normalizedEmail).isPresent()) {
			log.info("admin bootstrap skipped — account already exists for {}", normalizedEmail);
			return;
		}

		User admin = User.builder()
				.email(normalizedEmail)
				.passwordHash(passwordEncoder.encode(adminPassword))
				.role(User.Role.ADMIN)
				.build();
		userRepository.save(admin);
		log.info("admin bootstrap created ADMIN account for {}", normalizedEmail);
	}
}
