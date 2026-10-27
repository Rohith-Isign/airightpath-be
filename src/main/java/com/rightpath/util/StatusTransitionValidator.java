package com.rightpath.util;

import java.util.Map;
import java.util.Set;

import com.rightpath.enums.ApplicationStatus;

/**
 * Validates status transitions for job applications.
 * Enforces strict linear progression through the recruitment pipeline.
 */
public class StatusTransitionValidator {

    /**
     * Forward moves are strictly linear, but REJECTED is reachable from every
     * live stage. A candidate can leave the running at any point — they stop
     * replying after the ack, no-show the exam, cancel the interview — and the
     * pipeline previously only allowed rejection out of half the stages. That
     * forced a recruiter to push someone forward into a stage they had already
     * dropped out of, purely to gain the right to reject them there.
     */
    private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED_TRANSITIONS = Map.ofEntries(
        Map.entry(ApplicationStatus.APPLIED,               Set.of(ApplicationStatus.SHORTLISTED, ApplicationStatus.REJECTED)),
        // SHORTLISTED -> EXAM_SENT is the direct-assignment path: a recruiter who
        // already knows they want a candidate examined sends the paper straight
        // out of Applied, and the assignment shortlists them on the way through.
        // The ack / reconfirm round-trip stays available, it is just no longer
        // the only way to reach an exam.
        Map.entry(ApplicationStatus.SHORTLISTED,           Set.of(ApplicationStatus.ACKNOWLEDGED, ApplicationStatus.EXAM_SENT, ApplicationStatus.REJECTED)),
        Map.entry(ApplicationStatus.ACKNOWLEDGED,          Set.of(ApplicationStatus.ACKNOWLEDGED_BACK, ApplicationStatus.REJECTED)),
        Map.entry(ApplicationStatus.ACKNOWLEDGED_BACK,     Set.of(ApplicationStatus.RECONFIRMED, ApplicationStatus.REJECTED)),
        Map.entry(ApplicationStatus.RECONFIRMED,           Set.of(ApplicationStatus.EXAM_SENT, ApplicationStatus.REJECTED)),
        // EXAM_SENT -> EXAM_SENT, and EXAM_COMPLETED -> EXAM_SENT: an exam round
        // is not always a single assignment. A job may set aptitude first and add
        // the coding paper once it has been sat, and each assignment re-runs this
        // transition. Without these two the second assignment threw, so the
        // coding exam was never created and never reached the candidate.
        Map.entry(ApplicationStatus.EXAM_SENT,             Set.of(ApplicationStatus.EXAM_COMPLETED, ApplicationStatus.EXAM_SENT, ApplicationStatus.REJECTED)),
        Map.entry(ApplicationStatus.EXAM_COMPLETED,        Set.of(ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.REJECTED, ApplicationStatus.EXAM_SENT)),
        Map.entry(ApplicationStatus.INTERVIEW_SCHEDULED,   Set.of(ApplicationStatus.INTERVIEW_COMPLETED, ApplicationStatus.REJECTED)),
        Map.entry(ApplicationStatus.INTERVIEW_COMPLETED,   Set.of(ApplicationStatus.SELECTED, ApplicationStatus.REJECTED)),
        // A selected candidate who declines, or whose offer is withdrawn, still
        // has to land somewhere. Rejection is the only move out of SELECTED —
        // it stays terminal for every forward step.
        Map.entry(ApplicationStatus.SELECTED,              Set.of(ApplicationStatus.REJECTED))
    );

    /**
     * Validates whether a status transition is allowed.
     *
     * @param currentStatus the current status of the application
     * @param targetStatus  the desired next status
     * @throws IllegalStateException if the transition is not allowed
     */
    /**
     * Whether a transition is allowed, without throwing.
     *
     * <p>For steps that should happen when the pipeline is ready for them and be
     * skipped otherwise. Scheduling an interview is the case in point: it must
     * advance a candidate who has finished their exam, and must not fail for one
     * whose interview is being re-scheduled, since
     * {@code INTERVIEW_SCHEDULED → INTERVIEW_SCHEDULED} is not a legal move.</p>
     *
     * <p>Shares {@link #validate} rather than re-reading the table, so the two
     * can never disagree about what is allowed.</p>
     */
    public static boolean isAllowed(ApplicationStatus currentStatus, ApplicationStatus targetStatus) {
        try {
            validate(currentStatus, targetStatus);
            return true;
        } catch (IllegalStateException notAllowed) {
            return false;
        }
    }

    public static void validate(ApplicationStatus currentStatus, ApplicationStatus targetStatus) {
        if (currentStatus == null) {
            throw new IllegalStateException("Current status is missing. Cannot transition to " + targetStatus + ".");
        }

        if (currentStatus == ApplicationStatus.REJECTED) {
            throw new IllegalStateException("Cannot transition from REJECTED. The application has been closed.");
        }

        if (currentStatus == ApplicationStatus.SELECTED && targetStatus != ApplicationStatus.REJECTED) {
            throw new IllegalStateException(
                "Cannot transition from SELECTED to " + targetStatus + ". The application is already finalized; "
                + "only rejection (a declined or withdrawn offer) may move it.");
        }

        Set<ApplicationStatus> allowed = ALLOWED_TRANSITIONS.get(currentStatus);
        if (allowed == null || !allowed.contains(targetStatus)) {
            throw new IllegalStateException(
                "Cannot transition from " + currentStatus + " to " + targetStatus + ". " +
                "Allowed transitions from " + currentStatus + ": " +
                (allowed != null ? allowed : "none") + "."
            );
        }
    }
}
