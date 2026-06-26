package com.nextslope.profile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;

import lombok.RequiredArgsConstructor;

/**
 * Owner-scoped load/upsert of a {@link PreferenceProfile} plus the live country option list. The
 * profile is always resolved by the authenticated user's id — there is no addressable cross-user
 * route — so this service never takes an arbitrary profile id.
 */
@Service
@RequiredArgsConstructor
public class PreferenceProfileService {

	private final PreferenceProfileRepository preferenceProfileRepository;
	private final ResortRepository resortRepository;

	/** The user's saved values mapped to a form, or a defaults form when none exists. */
	@Transactional(readOnly = true)
	public PreferenceProfileForm loadFormForUser(Long userId) {
		return preferenceProfileRepository.findByUserId(userId)
				.map(PreferenceProfileService::toForm)
				.orElseGet(PreferenceProfileForm::defaults);
	}

	/** Upsert the authenticated user's profile from the submitted form. */
	@Transactional
	public void save(Long userId, PreferenceProfileForm form) {
		Set<String> regions = normalizeRegions(form);

		PreferenceProfile profile = preferenceProfileRepository.findByUserId(userId)
				.orElseGet(PreferenceProfile::new);
		profile.setUserId(userId);
		profile.setExperienceLevel(form.getExperienceLevel());
		profile.setDifficultyBand(form.getDifficultyBand());
		profile.setNoveltyPreference(form.getNoveltyPreference());
		profile.setRegionCountries(regions);

		preferenceProfileRepository.save(profile);
	}

	/** Distinct, sorted country values across the active resorts — the region option list. */
	@Transactional(readOnly = true)
	public List<String> availableCountries() {
		return resortRepository.findByActiveTrueOrderByCountryAscNameAsc().stream()
				.map(Resort::getCountry)
				.distinct()
				.sorted()
				.toList();
	}

	private Set<String> normalizeRegions(PreferenceProfileForm form) {
		if (form.isAnyRegion() || form.getRegionCountries() == null || form.getRegionCountries().isEmpty()) {
			return new LinkedHashSet<>();
		}
		List<String> available = availableCountries();
		Set<String> selected = new LinkedHashSet<>();
		for (String country : form.getRegionCountries()) {
			if (!available.contains(country)) {
				throw new UnknownRegionCountryException(country);
			}
			selected.add(country);
		}
		return selected;
	}

	private static PreferenceProfileForm toForm(PreferenceProfile profile) {
		PreferenceProfileForm form = new PreferenceProfileForm();
		form.setExperienceLevel(profile.getExperienceLevel());
		form.setDifficultyBand(profile.getDifficultyBand());
		form.setNoveltyPreference(profile.getNoveltyPreference());
		form.setRegionCountries(new ArrayList<>(profile.getRegionCountries()));
		form.setAnyRegion(profile.getRegionCountries().isEmpty());
		return form;
	}
}
