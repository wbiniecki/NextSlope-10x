package com.nextslope.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import com.nextslope.user.UserRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	// Dev-only: allow the in-memory H2 console (frames + no CSRF) on every profile
	// except prod. The dedicated chain matches only /h2-console/** so the relaxed
	// rules never touch application endpoints, and it is absent entirely in prod.
	@Bean
	@Order(1)
	@Profile("!prod")
	SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
		http
			.securityMatcher("/h2-console/**")
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.csrf(csrf -> csrf.disable())
			.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	@Order(2)
	SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository securityContextRepository,
			UserRepository userRepository) throws Exception {
		http
			.securityContext(sc -> sc.securityContextRepository(securityContextRepository))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/", "/index", "/login", "/signup", "/actuator/health", "/css/**", "/js/**",
						"/webjars/**")
				.permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(new StaleAuthenticatedSessionFilter(userRepository), AuthorizationFilter.class)
			.formLogin(form -> form
				.loginPage("/login")
				.defaultSuccessUrl("/", true)
				.permitAll())
			.logout(logout -> logout
				.logoutSuccessUrl("/?logout")
				.permitAll());
		return http.build();
	}
}
