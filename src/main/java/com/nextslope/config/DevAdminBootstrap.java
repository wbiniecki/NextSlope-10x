package com.nextslope.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.nextslope.user.EmailNormalizer;
import com.nextslope.user.User;
import com.nextslope.user.UserRepository;

@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(name = "nextslope.dev-admin.enabled", havingValue = "true")
public class DevAdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DevAdminBootstrap.class);

	static final String DEV_ADMIN_EMAIL = "admin@nextslope.local";
	static final String DEV_ADMIN_PASSWORD = "dev-admin";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	public DevAdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public void run(ApplicationArguments args) {
		bootstrap();
	}

	void bootstrap() {
		String normalizedEmail = EmailNormalizer.normalize(DEV_ADMIN_EMAIL);
		if (userRepository.findByEmail(normalizedEmail).isPresent()) {
			log.info("dev admin bootstrap skipped — account already exists for {}", normalizedEmail);
			return;
		}

		User admin = User.builder()
				.email(normalizedEmail)
				.passwordHash(passwordEncoder.encode(DEV_ADMIN_PASSWORD))
				.role(User.Role.ADMIN)
				.build();
		try {
			userRepository.save(admin);
			// Policy: never log plaintext credentials, even for local/dev bootstrap.
			log.info("dev admin bootstrap created ADMIN account for {}", normalizedEmail);
		} catch (DataIntegrityViolationException ex) {
			log.info("dev admin bootstrap skipped — account already exists for {}", normalizedEmail);
		}
	}
}
