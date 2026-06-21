package com.nextslope.user;

public final class EmailNormalizer {

	private EmailNormalizer() {
	}

	public static String normalize(String email) {
		if (email == null) {
			return "";
		}
		return email.trim().toLowerCase();
	}
}
