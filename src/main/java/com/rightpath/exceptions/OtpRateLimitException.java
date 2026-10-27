package com.rightpath.exceptions;

/** A new one-time code was asked for too soon after the last one. */
public class OtpRateLimitException extends RuntimeException {

    public OtpRateLimitException(String message) {
        super(message);
    }
}
