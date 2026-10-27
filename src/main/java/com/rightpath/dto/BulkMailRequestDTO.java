package com.rightpath.dto;

import java.util.List;

public class BulkMailRequestDTO {

    private List<String> emails;
    private String jobPrefix;
    private String dateTime;
    private String content;

    /**
     * Manual shortlist only: reopen a REJECTED application ("Shortlist Anyway").
     *
     * <p>REJECTED is terminal in the pipeline, so without this flag a rejected
     * candidate cannot be shortlisted and the request is reported as failed. Set it
     * to record a deliberate recruiter override; it is ignored for every other
     * current status, which still goes through the normal transition rules.</p>
     */
    private boolean override;

    public BulkMailRequestDTO() {}

    public List<String> getEmails() {
        return emails;
    }

    public void setEmails(List<String> emails) {
        this.emails = emails;
    }

    public String getJobPrefix() {
        return jobPrefix;
    }

    public void setJobPrefix(String jobPrefix) {
        this.jobPrefix = jobPrefix;
    }

    public String getDateTime() {
        return dateTime;
    }

    public void setDateTime(String dateTime) {
        this.dateTime = dateTime;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isOverride() {
        return override;
    }

    public void setOverride(boolean override) {
        this.override = override;
    }
}
