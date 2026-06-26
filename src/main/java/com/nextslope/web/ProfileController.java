package com.nextslope.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nextslope.profile.PreferenceProfileForm;
import com.nextslope.profile.PreferenceProfileService;
import com.nextslope.profile.UnknownRegionCountryException;
import com.nextslope.user.EmailNormalizer;
import com.nextslope.user.User;
import com.nextslope.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Owner-scoped preference-profile page. There is no id in the path — the profile is always the one
 * belonging to the authenticated principal, so there is no cross-user (IDOR) surface to defend.
 */
@Controller
@RequiredArgsConstructor
public class ProfileController {

	private final PreferenceProfileService preferenceProfileService;
	private final UserRepository userRepository;

	@GetMapping("/profile")
	public String form(@AuthenticationPrincipal UserDetails principal, Model model) {
		Long userId = currentUserId(principal);
		model.addAttribute("profileForm", preferenceProfileService.loadFormForUser(userId));
		model.addAttribute("availableCountries", preferenceProfileService.availableCountries());
		return "profile/form";
	}

	@PostMapping("/profile")
	public String save(@AuthenticationPrincipal UserDetails principal,
			@Valid @ModelAttribute("profileForm") PreferenceProfileForm profileForm,
			BindingResult bindingResult,
			Model model,
			RedirectAttributes redirectAttributes) {
		Long userId = currentUserId(principal);

		if (bindingResult.hasErrors()) {
			model.addAttribute("availableCountries", preferenceProfileService.availableCountries());
			return "profile/form";
		}

		try {
			preferenceProfileService.save(userId, profileForm);
		} catch (UnknownRegionCountryException ex) {
			bindingResult.rejectValue("regionCountries", "regionCountries.unknown",
					"One or more selected regions are not available");
			model.addAttribute("availableCountries", preferenceProfileService.availableCountries());
			return "profile/form";
		} catch (DataIntegrityViolationException ex) {
			// Concurrent double-submit before the profile row exists: the UNIQUE(user_id)
			// constraint rejects the second insert. Re-render so the user can retry (now an update).
			bindingResult.reject("profile.saveConflict",
					"Could not save your profile, please try again");
			model.addAttribute("availableCountries", preferenceProfileService.availableCountries());
			return "profile/form";
		}

		redirectAttributes.addFlashAttribute("profileSaved", true);
		return "redirect:/resorts";
	}

	private Long currentUserId(UserDetails principal) {
		User user = userRepository.findByEmail(EmailNormalizer.normalize(principal.getUsername()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		return user.getId();
	}
}
