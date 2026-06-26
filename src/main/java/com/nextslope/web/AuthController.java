package com.nextslope.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.EmailAlreadyExistsException;
import com.nextslope.user.EmailNormalizer;
import com.nextslope.user.RegistrationForm;
import com.nextslope.user.UserRegistrationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class AuthController {

	private final UserRegistrationService userRegistrationService;
	private final AppUserDetailsService appUserDetailsService;
	private final SecurityContextRepository securityContextRepository;
	private final SecurityContextHolderStrategy securityContextHolderStrategy =
			SecurityContextHolder.getContextHolderStrategy();

	@GetMapping("/login")
	public String login() {
		return "login";
	}

	@GetMapping("/signup")
	public String signupForm(Model model) {
		model.addAttribute("registrationForm", new RegistrationForm());
		return "signup";
	}

	@PostMapping("/signup")
	public String signupSubmit(@Valid @ModelAttribute("registrationForm") RegistrationForm registrationForm,
			BindingResult bindingResult,
			HttpServletRequest request,
			HttpServletResponse response,
			Model model) {
		if (bindingResult.hasErrors()) {
			return "signup";
		}

		try {
			userRegistrationService.register(registrationForm.getEmail(), registrationForm.getPassword());
		} catch (EmailAlreadyExistsException | DataIntegrityViolationException ex) {
			bindingResult.rejectValue("email", "email.exists", "An account with this email already exists");
			return "signup";
		}

		// Rotate the session ID on privilege change to prevent session fixation —
		// programmatic auto-login bypasses Spring's default SessionAuthenticationStrategy.
		// Only an existing (pre-auth) session can be fixed; if none exists yet, saveContext
		// will mint a fresh authenticated one below.
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}

		String normalizedEmail = EmailNormalizer.normalize(registrationForm.getEmail());
		UserDetails userDetails = appUserDetailsService.loadUserByUsername(normalizedEmail);
		UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
				userDetails, null, userDetails.getAuthorities());
		SecurityContext context = securityContextHolderStrategy.createEmptyContext();
		context.setAuthentication(auth);
		securityContextHolderStrategy.setContext(context);
		securityContextRepository.saveContext(context, request, response);

		// Onboard brand-new users straight into their preference profile (S-02).
		return "redirect:/profile";
	}
}
