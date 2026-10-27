package com.rightpath.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an assessment assignment actually achieved, per candidate.
 *
 * <p>Assigning and notifying are two different things and they fail
 * independently: the paper is stored and the assessment row written locally,
 * while the email goes out through a third-party SMTP host that rate-limits.
 * Reporting only "assigned successfully" hid the case that matters — a
 * candidate who has an exam waiting and was never told about it.</p>
 */
public class AssignmentReportDTO {

    /** Candidates whose assessment was created. */
    private final List<String> assigned = new ArrayList<>();

    /** Candidates who were also emailed their exam link. */
    private final List<String> notified = new ArrayList<>();

    /** Candidates whose assessment exists but whose email did not go out, and why. */
    private final List<Map<String, String>> notNotified = new ArrayList<>();

    public void recordNotified(String email) {
        assigned.add(email);
        notified.add(email);
    }

    public void recordNotNotified(String email, String reason) {
        assigned.add(email);
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("email", email);
        entry.put("reason", reason == null || reason.isBlank() ? "The notification email could not be sent." : reason);
        notNotified.add(entry);
    }

    /** True when every assigned candidate was also told about it. */
    public boolean allNotified() {
        return notNotified.isEmpty();
    }

    public List<String> getAssigned() {
        return List.copyOf(assigned);
    }

    public List<String> getNotified() {
        return List.copyOf(notified);
    }

    public List<Map<String, String>> getNotNotified() {
        return List.copyOf(notNotified);
    }

    public int getAssignedCount() {
        return assigned.size();
    }

    public int getNotifiedCount() {
        return notified.size();
    }

    public int getNotNotifiedCount() {
        return notNotified.size();
    }

    /**
     * The line to show the recruiter. When some candidates went unnotified it
     * names the recovery, because the assessments are already in place and only
     * the mail needs repeating.
     */
    public String getMessage() {
        if (assigned.isEmpty()) {
            return "No assessments were assigned.";
        }
        if (allNotified()) {
            return "Assessments assigned and exam links sent.";
        }
        return "Assessments assigned, but " + notNotified.size() + " of " + assigned.size()
                + " candidates could not be emailed. Their exams are ready — use Send Exam Link to try again.";
    }
}
