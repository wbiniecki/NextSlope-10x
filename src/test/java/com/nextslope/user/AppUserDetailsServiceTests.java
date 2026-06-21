package com.nextslope.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.nextslope.config.SecurityConfig;

@DataJpaTest
@Import({AppUserDetailsService.class, SecurityConfig.class})
class AppUserDetailsServiceTests {

	@Autowired
	private AppUserDetailsService appUserDetailsService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void loadsPersistedUserWithCorrectUsernameAndRole() {
		userRepository.save(User.builder()
				.email("skier@example.com")
				.passwordHash(passwordEncoder.encode("secret"))
				.role(User.Role.USER)
				.build());

		UserDetails details = appUserDetailsService.loadUserByUsername("skier@example.com");

		assertThat(details.getUsername()).isEqualTo("skier@example.com");
		assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_USER");
	}

	@Test
	void unknownEmailThrowsUsernameNotFoundException() {
		assertThatThrownBy(() -> appUserDetailsService.loadUserByUsername("missing@example.com"))
				.isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	void mixedCaseLookupResolvesLowercaseStoredRow() {
		userRepository.save(User.builder()
				.email("alice@x.com")
				.passwordHash(passwordEncoder.encode("secret"))
				.role(User.Role.ADMIN)
				.build());

		UserDetails details = appUserDetailsService.loadUserByUsername("Alice@x.com");

		assertThat(details.getUsername()).isEqualTo("alice@x.com");
		assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_ADMIN");
	}
}
