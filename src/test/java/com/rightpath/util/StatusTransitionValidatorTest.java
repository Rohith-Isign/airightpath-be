package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.rightpath.enums.ApplicationStatus;

/**
 * Pins the pipeline's shape: forward moves stay linear, rejection stays
 * reachable from every live stage, and neither terminal state can be walked
 * back into the pipeline.
 */
class StatusTransitionValidatorTest {

	/**
	 * The rule the admin UI depends on — Send Rejection is offered on every
	 * stage, so every live stage has to accept the move. REJECTED itself is
	 * excluded: it is terminal, and reopening it is the shortlist override's
	 * job, which bypasses this validator deliberately.
	 */
	@ParameterizedTest
	@EnumSource(value = ApplicationStatus.class, names = "REJECTED", mode = EnumSource.Mode.EXCLUDE)
	void everyLiveStageCanBeRejected(ApplicationStatus from) {
		assertDoesNotThrow(() -> StatusTransitionValidator.validate(from, ApplicationStatus.REJECTED));
	}

	@Test
	void forwardPathRemainsLinear() {
		assertDoesNotThrow(() -> {
			StatusTransitionValidator.validate(ApplicationStatus.APPLIED, ApplicationStatus.SHORTLISTED);
			StatusTransitionValidator.validate(ApplicationStatus.SHORTLISTED, ApplicationStatus.ACKNOWLEDGED);
			StatusTransitionValidator.validate(ApplicationStatus.ACKNOWLEDGED, ApplicationStatus.ACKNOWLEDGED_BACK);
			StatusTransitionValidator.validate(ApplicationStatus.ACKNOWLEDGED_BACK, ApplicationStatus.RECONFIRMED);
			StatusTransitionValidator.validate(ApplicationStatus.RECONFIRMED, ApplicationStatus.EXAM_SENT);
			StatusTransitionValidator.validate(ApplicationStatus.EXAM_SENT, ApplicationStatus.EXAM_COMPLETED);
			StatusTransitionValidator.validate(ApplicationStatus.EXAM_COMPLETED, ApplicationStatus.INTERVIEW_SCHEDULED);
			StatusTransitionValidator.validate(ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEW_COMPLETED);
			StatusTransitionValidator.validate(ApplicationStatus.INTERVIEW_COMPLETED, ApplicationStatus.SELECTED);
		});
	}

	/** Reaching RECONFIRMED early would let a candidate sit an exam unacknowledged. */
	@Test
	void reconfirmationStaysReachableOnlyFromAckBack() {
		assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(ApplicationStatus.ACKNOWLEDGED, ApplicationStatus.RECONFIRMED));
		assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(ApplicationStatus.SHORTLISTED, ApplicationStatus.RECONFIRMED));
	}

	/**
	 * Direct assignment: an applicant is shortlisted and mailed the paper in one
	 * action, so the pair of steps it takes has to be legal end to end.
	 */
	@Test
	void applicantCanBeSentAnExamByWayOfShortlisting() {
		assertDoesNotThrow(() -> {
			StatusTransitionValidator.validate(ApplicationStatus.APPLIED, ApplicationStatus.SHORTLISTED);
			StatusTransitionValidator.validate(ApplicationStatus.SHORTLISTED, ApplicationStatus.EXAM_SENT);
		});
	}

	/** The shortlisting step is not optional — APPLIED may not jump to the exam. */
	@Test
	void applicantCannotSkipStraightToExamSent() {
		assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(ApplicationStatus.APPLIED, ApplicationStatus.EXAM_SENT));
	}

	/** A second paper in the same round re-runs the assignment transition. */
	@Test
	void examCanBeAssignedTwiceInOneRound() {
		assertDoesNotThrow(() -> StatusTransitionValidator.validate(ApplicationStatus.EXAM_SENT, ApplicationStatus.EXAM_SENT));
		assertDoesNotThrow(() -> StatusTransitionValidator.validate(ApplicationStatus.EXAM_COMPLETED, ApplicationStatus.EXAM_SENT));
	}

	@Test
	void rejectionIsTerminal() {
		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(ApplicationStatus.REJECTED, ApplicationStatus.SHORTLISTED));
		assertTrue(thrown.getMessage().contains("REJECTED"));
	}

	/** Rejection may close out a selected candidate, but nothing may reopen one. */
	@Test
	void selectionYieldsOnlyToRejection() {
		assertDoesNotThrow(() -> StatusTransitionValidator.validate(ApplicationStatus.SELECTED, ApplicationStatus.REJECTED));
		assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(ApplicationStatus.SELECTED, ApplicationStatus.SHORTLISTED));
		assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(ApplicationStatus.SELECTED, ApplicationStatus.INTERVIEW_SCHEDULED));
	}

	@Test
	void missingCurrentStatusIsRejected() {
		assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(null, ApplicationStatus.SHORTLISTED));
	}

	@Test
	void backwardsStepsStayBlocked() {
		assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.APPLIED));
		assertThrows(IllegalStateException.class,
				() -> StatusTransitionValidator.validate(ApplicationStatus.EXAM_COMPLETED, ApplicationStatus.SHORTLISTED));
	}
}
