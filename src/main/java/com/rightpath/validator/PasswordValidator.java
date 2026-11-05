package com.rightpath.validator;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.rightpath.exceptions.PasswordSizeException;

@Component
public class PasswordValidator {

	// Minimum size for a valid password, configurable via application properties
	@Value("${password.minsize}")
	private int minSize;

	// Maximum size for a valid password, configurable via application properties
	@Value("${password.maxsize}")
	private int maxSize;

	/**
	 * Validates the size of the given password.
	 *
	 * @param password The password to validate.
	 * @throws PasswordSizeException if the password is null, too short, or too
	 *                               long.
	 */
	public void passwordSizeChecker(String password) {
		try {
			int passwordSize = password.length();

			// Validate password size against the defined limits
			if (passwordSize < 8 || passwordSize > 20) {
				throw new PasswordSizeException("Password size should in between " + minSize + " to " + maxSize);
			}
		} catch (NullPointerException e) {

			// Handle null passwords explicitly
			throw new PasswordSizeException("Password should not be null");
		}
	}

}
