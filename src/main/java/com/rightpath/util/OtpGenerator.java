package com.rightpath.util;

import java.security.SecureRandom;

public class OtpGenerator {

	// Characters allowed in the OTP, including letters, numbers, and special
	// characters
	private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@$%&*";
	// Length of the OTP to be generated
	private static final int OTP_LENGTH = 6;
	// SecureRandom instance for cryptographically strong random number generation
	private static final SecureRandom random = new SecureRandom();

	/**
	 * Generates a random OTP with a fixed length defined by `OTP_LENGTH`. The OTP
	 * includes characters from the predefined `CHARACTERS` set.
	 *
	 * @return A randomly generated OTP as a String.
	 */
	public static String generateOtp() {
		// StringBuilder for efficient string concatenation
		StringBuilder otp = new StringBuilder(OTP_LENGTH);

		// Generate each character of the OTP randomly
		for (int i = 0; i < OTP_LENGTH; i++) {
			otp.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
		}

		// The OTP value is a credential and must never be logged.
		return otp.toString();
	}

}
