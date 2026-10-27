package com.rightpath.dto;

/**
 * What ATS screening did — or deliberately did not do — to one candidate.
 *
 * <p>A skipped row is not a failure: it is a candidate the screen is not allowed to
 * touch (already past shortlisting, or a finalised rejection). {@code reason} carries
 * the explanation verbatim to the UI so the recruiter is told why, rather than being
 * left to infer it from an unchanged status.</p>
 *
 * @param email        candidate's email; the key the caller selected by
 * @param fullName     candidate's name, for display in the result list
 * @param matchPercent resume score against the job's key skills; null only when no
 *                     application exists, since a skipped row is still scored so the
 *                     UI can show what the candidate <em>would</em> have scored
 * @param status       application status after the run (unchanged for a skipped row)
 * @param screened     whether the decision was recomputed and persisted
 * @param reason       why the row was skipped; null when screened
 */
public record ScreeningResultDTO(
        String email,
        String fullName,
        Double matchPercent,
        String status,
        boolean screened,
        String reason) {

    public static ScreeningResultDTO screened(String email, String fullName, double matchPercent, String status) {
        return new ScreeningResultDTO(email, fullName, matchPercent, status, true, null);
    }

    public static ScreeningResultDTO skipped(String email, String fullName, double matchPercent,
                                             String status, String reason) {
        return new ScreeningResultDTO(email, fullName, matchPercent, status, false, reason);
    }

    /** An email the caller asked for that has no application on this job. */
    public static ScreeningResultDTO notFound(String email) {
        return new ScreeningResultDTO(email, null, null, null, false,
                "No application found for this candidate on this job.");
    }
}
