package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.rightpath.enums.ApplicationStatus;

/**
 * The rule interview scheduling relies on when advancing a candidate.
 *
 * <p>Assigning an interview used to set a free-text {@code interview} column and
 * leave {@code status} on EXAM_COMPLETED, so a scheduled candidate still read as
 * sitting in the exam stage. It now advances the status — but only where the move
 * is legal, because re-scheduling an interview is routine and
 * {@code INTERVIEW_SCHEDULED → INTERVIEW_SCHEDULED} is not a permitted
 * transition. Throwing there would fail the second attempt and create no
 * schedule at all.</p>
 */
class InterviewSchedulingTransitionTest {

    @Test
    void aCandidateWhoFinishedTheExamAdvances() {
        assertTrue(StatusTransitionValidator.isAllowed(
                ApplicationStatus.EXAM_COMPLETED, ApplicationStatus.INTERVIEW_SCHEDULED));
    }

    @Test
    void reschedulingIsSkippedRatherThanRejected() {
        // The case that makes this a guard instead of a validate call.
        assertFalse(StatusTransitionValidator.isAllowed(
                ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEW_SCHEDULED));

        // And the same check throwing is what scheduling must not do.
        assertThrows(IllegalStateException.class, () -> StatusTransitionValidator.validate(
                ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEW_SCHEDULED));
    }

    @Test
    void schedulingNeverRevivesAClosedApplication() {
        // A rejected or already-selected candidate must not be quietly pulled
        // back into the interview stage by an errant bulk assignment.
        assertFalse(StatusTransitionValidator.isAllowed(
                ApplicationStatus.REJECTED, ApplicationStatus.INTERVIEW_SCHEDULED));
        assertFalse(StatusTransitionValidator.isAllowed(
                ApplicationStatus.SELECTED, ApplicationStatus.INTERVIEW_SCHEDULED));
        assertFalse(StatusTransitionValidator.isAllowed(
                ApplicationStatus.INTERVIEW_COMPLETED, ApplicationStatus.INTERVIEW_SCHEDULED));
    }

    @Test
    void schedulingBeforeTheExamIsNotAShortcut() {
        // Scheduling an interview must not skip the exam stage for someone who
        // has not sat one; those candidates keep their existing status.
        assertFalse(StatusTransitionValidator.isAllowed(
                ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEW_SCHEDULED));
        assertFalse(StatusTransitionValidator.isAllowed(
                ApplicationStatus.EXAM_SENT, ApplicationStatus.INTERVIEW_SCHEDULED));
    }

    @Test
    void isAllowedAndValidateAgreeOnEveryStatus() {
        // They share one implementation; this pins that they cannot drift into
        // disagreeing about any starting point.
        for (ApplicationStatus from : ApplicationStatus.values()) {
            boolean allowed = StatusTransitionValidator.isAllowed(from, ApplicationStatus.INTERVIEW_SCHEDULED);
            boolean threw = false;
            try {
                StatusTransitionValidator.validate(from, ApplicationStatus.INTERVIEW_SCHEDULED);
            } catch (IllegalStateException e) {
                threw = true;
            }
            assertTrue(allowed != threw, () -> "isAllowed and validate disagree for " + from);
        }
    }

    @Test
    void aMissingStatusIsNotTreatedAsPermission() {
        assertFalse(StatusTransitionValidator.isAllowed(null, ApplicationStatus.INTERVIEW_SCHEDULED));
    }
}
