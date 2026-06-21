package com.nextslope;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nextslope.config.SecurityConfig;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.EmailAlreadyExistsException;
import com.nextslope.user.UserRegistrationService;
import com.nextslope.user.UserRepository;
import com.nextslope.web.AuthController;

@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
class SignupWebMvcTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserRegistrationService userRegistrationService;

	@MockitoBean
	private AppUserDetailsService appUserDetailsService;

	@MockitoBean
	private UserRepository userRepository;

	@Test
	void getSignupRendersForm() throws Exception {
		mockMvc.perform(get("/signup"))
				.andExpect(status().isOk())
				.andExpect(view().name("signup"))
				.andExpect(model().attributeExists("registrationForm"));
	}

	@Test
	void postSignupWithMalformedEmailRendersEmailErrorMessage() throws Exception {
		mockMvc.perform(post("/signup")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("email", "!!!!!!!!@££££@gmail.com")
						.param("password", "secret123"))
				.andExpect(status().isOk())
				.andExpect(view().name("signup"))
				.andExpect(model().hasErrors())
				.andExpect(content().string(containsString("Enter a valid email address")));
	}

	@Test
	void postSignupWithInvalidInputRendersFormWithErrors() throws Exception {
		mockMvc.perform(post("/signup")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("email", "not-an-email")
						.param("password", "short"))
				.andExpect(status().isOk())
				.andExpect(view().name("signup"))
				.andExpect(model().hasErrors());
	}

	@Test
	void postSignupWithDuplicateEmailRendersEmailFieldErrorNotRedirect() throws Exception {
		when(userRegistrationService.register(anyString(), anyString()))
				.thenThrow(new EmailAlreadyExistsException("taken@example.com"));

		mockMvc.perform(post("/signup")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("email", "taken@example.com")
						.param("password", "secret123"))
				.andExpect(status().isOk())
				.andExpect(view().name("signup"))
				.andExpect(model().attributeHasFieldErrors("registrationForm", "email"))
				.andExpect(content().string(containsString("An account with this email already exists")));
	}

	@Test
	void postSignupWhenUniqueConstraintRacesRendersEmailFieldErrorNotRedirect() throws Exception {
		// Pre-check passed but the DB UNIQUE(email) backstop fired (concurrent signup) —
		// the controller must map it to a field error, never a 500 or a redirect.
		when(userRegistrationService.register(anyString(), anyString()))
				.thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

		mockMvc.perform(post("/signup")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("email", "racer@example.com")
						.param("password", "secret123"))
				.andExpect(status().isOk())
				.andExpect(view().name("signup"))
				.andExpect(model().attributeHasFieldErrors("registrationForm", "email"))
				.andExpect(content().string(containsString("An account with this email already exists")));
	}
}
