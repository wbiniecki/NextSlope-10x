package com.nextslope.profile;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Form-bound view of a preference profile — never the entity. The three axes are required; region is
 * expressed as an "any region" toggle plus a list of selected countries (validity against the live
 * vocabulary is enforced in {@link PreferenceProfileService}, not as a static annotation).
 */
@Getter
@Setter
@NoArgsConstructor
public class PreferenceProfileForm {

	@NotNull(message = "Select your experience level")
	private ExperienceLevel experienceLevel;

	@NotNull(message = "Select a difficulty preference")
	private DifficultyBand difficultyBand;

	@NotNull(message = "Select a novelty preference")
	private NoveltyPreference noveltyPreference;

	private boolean anyRegion;

	private List<String> regionCountries = new ArrayList<>();

	/** Defaults shown to a brand-new user with no saved profile. */
	public static PreferenceProfileForm defaults() {
		PreferenceProfileForm form = new PreferenceProfileForm();
		form.setExperienceLevel(ExperienceLevel.INTERMEDIATE);
		form.setDifficultyBand(DifficultyBand.BALANCED);
		form.setNoveltyPreference(NoveltyPreference.REVISIT_OKAY);
		form.setAnyRegion(true);
		form.setRegionCountries(new ArrayList<>());
		return form;
	}
}
