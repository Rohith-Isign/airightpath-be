package com.rightpath.exceptions;

import java.time.LocalDate;

/**
 * Thrown when an update sets a <em>new</em> application deadline that has already
 * passed.
 *
 * <p>Leaving an expired job's own deadline untouched is allowed — an admin fixing a
 * typo on a closed posting must not be forced to reopen it — so this only fires
 * when the deadline actually changes to a past date. Handled as HTTP 400 with code
 * {@code JOB_DEADLINE_IN_PAST}.
 */
public class JobDeadlineInPastException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JobDeadlineInPastException(LocalDate requestedDeadline, LocalDate today) {
        super("Application deadline " + requestedDeadline + " is in the past (today is " + today
                + "). Leave the existing deadline unchanged, or pick today or later.");
    }
}
