package com.nextslope.resort;

public class ConcurrentResortUpdateException extends RuntimeException {

	public ConcurrentResortUpdateException(Long resortId, Throwable cause) {
		super("Resort " + resortId + " was updated concurrently", cause);
	}
}
