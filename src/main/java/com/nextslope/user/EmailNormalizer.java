package com.nextslope.user;

public final class EmailNormalizer {

	private EmailNormalizer() {
	}

	public static String normalize(String email) {
		return email.trim().toLowerCase();
	}
}
