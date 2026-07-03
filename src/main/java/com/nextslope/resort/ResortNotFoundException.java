package com.nextslope.resort;

/**
 * Raised when an admin lookup targets a resort id that does not exist. The web layer maps this to
 * {@code 404}; distinct from {@link com.nextslope.visited.ResortNotFoundException}, which signals
 * "no active resort" in the visited-mark flow.
 */
public class ResortNotFoundException extends RuntimeException {

	public ResortNotFoundException(Long resortId) {
		super("No resort with id " + resortId);
	}
}
