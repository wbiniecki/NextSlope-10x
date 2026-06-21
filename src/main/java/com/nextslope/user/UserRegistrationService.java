package com.nextslope.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserRegistrationService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	public User register(String email, String rawPassword) {
		String normalizedEmail = EmailNormalizer.normalize(email);

		if (userRepository.findByEmail(normalizedEmail).isPresent()) {
			throw new EmailAlreadyExistsException(normalizedEmail);
		}

		User user = User.builder()
				.email(normalizedEmail)
				.passwordHash(passwordEncoder.encode(rawPassword))
				.role(User.Role.USER)
				.build();

		return userRepository.save(user);
	}
}
