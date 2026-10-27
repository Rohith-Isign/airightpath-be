package com.rightpath.exceptions;

/**
 * Thrown when an update tries to change a job post's {@code jobPrefix}.
 *
 * <p>The prefix is the key applications, assessments, evaluation categories, job
 * prompts and the public apply link are all filed under, so it is corrected by
 * creating a new posting — never by renaming a live one. Handled as HTTP 400 with
 * code {@code JOB_PREFIX_IMMUTABLE}.
 */
public class JobPrefixImmutableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JobPrefixImmutableException(String storedPrefix, String requestedPrefix) {
        super("Job prefix cannot be changed: this job is '" + storedPrefix + "', the request sent '"
                + requestedPrefix + "'. Applications and assessments are filed under the stored prefix.");
    }
}
