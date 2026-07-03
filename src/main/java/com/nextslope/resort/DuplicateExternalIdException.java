package com.nextslope.resort;

/**
 * Raised when a non-null {@code externalId} is already assigned to a different resort.
 */
public class DuplicateExternalIdException extends RuntimeException {

	public DuplicateExternalIdException(Long externalId) {
		super("External ID " + externalId + " is already in use");
	}
}
