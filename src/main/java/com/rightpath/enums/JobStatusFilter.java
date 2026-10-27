package com.rightpath.enums;

/**
 * Lifecycle bucket used to filter job postings by their application deadline.
 *
 * <p>A posting is {@code ACTIVE} while its {@code applicationDeadline} is today
 * or later (evaluated as a <em>date</em> in the configured business timezone),
 * and {@code EXPIRED} once that date has passed. Postings with no deadline at
 * all never expire and therefore count as {@code ACTIVE}.</p>
 */
public enum JobStatusFilter {

    /** Deadline is today or in the future, or there is no deadline. */
    ACTIVE,

    /** Deadline is strictly before today. */
    EXPIRED,

    /** No deadline filtering at all. */
    ALL;

    /** Value applied when a request omits the {@code status} parameter. */
    public static final JobStatusFilter DEFAULT = ACTIVE;

    /**
     * Parses the {@code status} query parameter, tolerating any casing and
     * surrounding whitespace ({@code active}, {@code Active}, {@code ACTIVE}).
     *
     * @param raw the raw parameter value; {@code null} or blank selects {@link #DEFAULT}
     * @return the requested filter
     * @throws IllegalArgumentException if the value is not a known bucket
     */
    public static JobStatusFilter fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String candidate = raw.trim().toUpperCase();
        for (JobStatusFilter value : values()) {
            if (value.name().equals(candidate)) {
                return value;
            }
        }
        throw new IllegalArgumentException(
                "Invalid status '" + raw + "'. Allowed values: ACTIVE, EXPIRED, ALL");
    }
}
