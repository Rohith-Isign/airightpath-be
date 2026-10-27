package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A candidate must not be penalised for the platform's own bookkeeping.
 *
 * <p>Every proctoring event used to increment the warning count, and the count
 * is what fails an interview — at the ceiling the schedule is marked
 * {@code FAILED} with {@code PROCTORING_VIOLATION}. So a candidate whose
 * recordings uploaded perfectly collected two warnings for it, one per stream,
 * because a successful upload reports itself through the same channel.</p>
 */
class ProctoringEventSeverityTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"no_face",
			"multiple_faces",
			"looking_away",
			"fullscreen_exit",
			"devtools",
			"screen_share_denied",
			"screen_share_stopped",
			"mobile_malpractice",
			"room_multiple_faces" })
	void realViolationsStillCount(String eventType) {
		assertTrue(ProctoringEventSeverity.countsAsWarning(eventType), eventType + " should count");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"recording_uploaded",
			"recording_upload_failed",
			"recording_upload_retry",
			"recording_upload_abandoned",
			"screen_share_resumed",
			"questions_unanswered" })
	void theRecordingAuditIsNotAnAccusation(String eventType) {
		assertFalse(ProctoringEventSeverity.countsAsWarning(eventType), eventType + " should not count");
	}

	/**
	 * A successful upload counting against the candidate is the specific bug
	 * this exists to prevent, so it gets its own named test rather than
	 * hiding in the list above.
	 */
	@Test
	void aSuccessfulUploadIsNeverAWarning() {
		assertFalse(ProctoringEventSeverity.countsAsWarning("recording_uploaded"));
	}

	/**
	 * Putting a stopped share back is the candidate doing the right thing.
	 * Stopping it already counted; charging them again for fixing it is a
	 * reason not to fix it.
	 */
	@Test
	void resumingAScreenShareIsNotASecondViolation() {
		assertTrue(ProctoringEventSeverity.countsAsWarning("screen_share_stopped"));
		assertFalse(ProctoringEventSeverity.countsAsWarning("screen_share_resumed"));
	}

	/**
	 * Unknown types count. Of the two ways to be wrong, silently ignoring a
	 * real violation guts the proctoring, while an informational event that
	 * was not declared here shows up as an obviously wrong warning — which is
	 * the mistake that gets noticed and fixed.
	 */
	@Test
	void anUnrecognisedEventCounts() {
		assertTrue(ProctoringEventSeverity.countsAsWarning("some_future_violation"));
	}

	/** An event that cannot be identified is not one that can be cleared. */
	@Test
	void aMissingTypeCounts() {
		assertTrue(ProctoringEventSeverity.countsAsWarning(null));
		assertTrue(ProctoringEventSeverity.countsAsWarning("   "));
	}

	@Test
	void matchingIgnoresCaseAndSurroundingSpace() {
		assertFalse(ProctoringEventSeverity.countsAsWarning("  Recording_Uploaded  "));
	}

	@Test
	void isInformationalIsTheExactOpposite() {
		assertTrue(ProctoringEventSeverity.isInformational("recording_uploaded"));
		assertFalse(ProctoringEventSeverity.isInformational("no_face"));
	}
}
