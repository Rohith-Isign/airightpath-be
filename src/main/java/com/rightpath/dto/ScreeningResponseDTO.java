package com.rightpath.dto;

import java.util.List;

/**
 * Result of one ATS screening run.
 *
 * <p>The counts let the UI summarise the run without walking {@code results}
 * ("12 screened, 3 skipped"), and {@code results} preserves the order the caller
 * asked for so a scoped run can be zipped back onto the selected rows.</p>
 *
 * @param jobPrefix        job that was screened
 * @param threshold        score at or above which a candidate is shortlisted
 * @param scope            {@code "SELECTED"} or {@code "ALL"} — what the run covered
 * @param screenedCount    rows whose decision was recomputed and persisted
 * @param skippedCount     rows the screen was not allowed to touch
 * @param notFoundCount    requested emails with no application on this job
 * @param shortlistedCount screened rows that landed on SHORTLISTED
 * @param rejectedCount    screened rows that landed on REJECTED
 * @param message          human-readable summary of the run
 * @param results          per-candidate outcome, in request order
 */
public record ScreeningResponseDTO(
        String jobPrefix,
        double threshold,
        String scope,
        int screenedCount,
        int skippedCount,
        int notFoundCount,
        int shortlistedCount,
        int rejectedCount,
        String message,
        List<ScreeningResultDTO> results) {

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_SELECTED = "SELECTED";

    /** Tallies the per-candidate outcomes into the summary counts. */
    public static ScreeningResponseDTO of(String jobPrefix, double threshold, String scope,
                                          List<ScreeningResultDTO> results) {
        int screened = 0;
        int skipped = 0;
        int notFound = 0;
        int shortlisted = 0;
        int rejected = 0;

        for (ScreeningResultDTO result : results) {
            if (result.screened()) {
                screened++;
                if ("SHORTLISTED".equals(result.status())) {
                    shortlisted++;
                } else if ("REJECTED".equals(result.status())) {
                    rejected++;
                }
            } else if (result.status() == null) {
                notFound++;
            } else {
                skipped++;
            }
        }

        StringBuilder message = new StringBuilder()
                .append(screened).append(screened == 1 ? " candidate screened" : " candidates screened");
        if (skipped > 0) {
            message.append(", ").append(skipped).append(" skipped");
        }
        if (notFound > 0) {
            message.append(", ").append(notFound).append(" not found");
        }
        message.append('.');

        return new ScreeningResponseDTO(jobPrefix, threshold, scope, screened, skipped, notFound,
                shortlisted, rejected, message.toString(), results);
    }
}
