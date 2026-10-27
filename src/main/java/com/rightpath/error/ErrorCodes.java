package com.rightpath.error;

/**
 * Machine-readable error codes returned in the {@code code} field of every
 * {@link ApiError}. Clients can switch on these regardless of the human-readable
 * message (which may be localized or reworded).
 */
public final class ErrorCodes {
    private ErrorCodes() {
    }

    public static final String AUTH_BAD_REQUEST = "AUTH_BAD_REQUEST";
    public static final String AUTH_UNAUTHORIZED = "AUTH_UNAUTHORIZED";
    public static final String AUTH_INVALID_TOKEN = "AUTH_INVALID_TOKEN";
    public static final String AUTH_INVALID_REFRESH = "AUTH_INVALID_REFRESH";
    public static final String AUTH_REFRESH_REUSE_DETECTED = "AUTH_REFRESH_REUSE_DETECTED";
    public static final String AUTH_FORBIDDEN = "AUTH_FORBIDDEN";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // Generic request-level
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String CONFLICT = "CONFLICT";

    // Domain
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String USER_ALREADY_EXISTS = "USER_ALREADY_EXISTS";
    public static final String USER_INACTIVE = "USER_INACTIVE";
    public static final String PASSWORD_POLICY = "PASSWORD_POLICY";
    public static final String PASSWORD_MISMATCH = "PASSWORD_MISMATCH";
    public static final String STORAGE_ERROR = "STORAGE_ERROR";
    public static final String APPLICATION_DEADLINE_PASSED = "APPLICATION_DEADLINE_PASSED";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String BUSINESS_ERROR = "BUSINESS_ERROR";

    // Job posts
    /** No job post exists for the requested id. */
    public static final String JOB_NOT_FOUND = "JOB_NOT_FOUND";
    /** An update tried to change {@code jobPrefix}, which everything else keys off. */
    public static final String JOB_PREFIX_IMMUTABLE = "JOB_PREFIX_IMMUTABLE";
    /** An update set a *new* application deadline that has already passed. */
    public static final String JOB_DEADLINE_IN_PAST = "JOB_DEADLINE_IN_PAST";

    public static final String COMPILER_ERROR = "COMPILER_ERROR";
    /** The submission named a language this platform does not run. */
    public static final String COMPILER_UNSUPPORTED_LANGUAGE = "COMPILER_UNSUPPORTED_LANGUAGE";
    /**
     * The toolchain is missing or broken on the server. Ours to fix, not the
     * candidate's — the exam screen should offer a retry, not blame their code.
     */
    public static final String COMPILER_UNAVAILABLE = "COMPILER_UNAVAILABLE";

    // AI Service
    public static final String AI_SERVICE_TIMEOUT = "AI_SERVICE_TIMEOUT";
    public static final String AI_SERVICE_ERROR = "AI_SERVICE_ERROR";

    /**
     * The mail host refused or dropped the message — rate limits, auth, an
     * unreachable relay. Upstream and usually transient, so the client should
     * offer to retry the send rather than report the whole operation as broken.
     */
    public static final String EMAIL_DELIVERY_FAILED = "EMAIL_DELIVERY_FAILED";
}
