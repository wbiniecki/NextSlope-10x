package com.nextslope.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

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
	@Order(2)
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/", "/index", "/actuator/health", "/css/**", "/js/**", "/webjars/**").permitAll()
				.anyRequest().authenticated())
			.formLogin(Customizer.withDefaults());
		return http.build();
	}
}
