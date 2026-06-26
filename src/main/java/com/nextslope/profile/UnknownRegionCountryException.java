package com.nextslope.profile;

/**
 * Raised when a submitted region country is not part of the live resort vocabulary
 * ({@link PreferenceProfileService#availableCountries()}). The controller maps it to a field error.
 */
public class UnknownRegionCountryException extends RuntimeException {

	public UnknownRegionCountryException(String country) {
		super("Unknown region country: " + country);
	}
}
