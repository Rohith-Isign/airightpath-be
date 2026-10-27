package com.rightpath.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/job-applications/screen}.
 *
 * <p>{@code emails} scopes the run: supply the candidates to (re)screen, or omit it
 * (null or empty) to screen the whole job. Screening a subset exists so a recruiter
 * can re-score a handful of rows — or a single rejection — without disturbing the
 * rest of the pipeline.</p>
 */
public class ScreeningRequestDTO {

    private String jobPrefix;

    /** Candidates to screen; null/empty means every applicant on the job. */
    private List<String> emails;

    public ScreeningRequestDTO() {}

    public String getJobPrefix() {
        return jobPrefix;
    }

    public void setJobPrefix(String jobPrefix) {
        this.jobPrefix = jobPrefix;
    }

    public List<String> getEmails() {
        return emails;
    }

    public void setEmails(List<String> emails) {
        this.emails = emails;
    }
}
