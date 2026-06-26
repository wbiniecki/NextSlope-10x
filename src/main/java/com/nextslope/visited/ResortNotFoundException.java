package com.nextslope.visited;

/**
 * Raised when a mark is attempted against a resort that does not exist or is inactive. The web layer
 * translates this into a {@code 404}; unmark never raises it (a mark can always be cleared, even for a
 * later-deactivated resort).
 */
public class ResortNotFoundException extends RuntimeException {

	public ResortNotFoundException(Long resortId) {
		super("No active resort with id " + resortId);
	}
}
