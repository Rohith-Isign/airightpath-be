package com.rightpath.util;

import java.util.Locale;
import java.util.Set;

/**
 * Which proctoring events count against a candidate, and which are only a
 * record of what happened.
 *
 * <p>Every event the client sent used to increment the warning count, and the
 * count is what fails an interview: at the ceiling the schedule is marked
 * {@code FAILED} with {@code PROCTORING_VIOLATION} and the interview ends. So
 * a candidate whose recordings uploaded perfectly collected two warnings for
 * it — one for the camera, one for the screen — because the upload reports
 * {@code recording_uploaded} through the same channel. Resuming a screen share
 * after an accidental stop cost another, on top of the one for stopping.</p>
 *
 * <p>It went unnoticed because the ceiling is set very high in the
 * environments this was tested in. Somewhere with a realistic ceiling, a
 * candidate who did nothing wrong fails for successfully uploading their own
 * evidence.</p>
 *
 * <h2>Which way to fail</h2>
 *
 * <p>Unknown event types <strong>count</strong>. The two mistakes available
 * are not symmetrical, but neither is safe: silently ignoring a real violation
 * guts the proctoring, while silently counting an informational event fails an
 * innocent candidate. Counting by default means a new violation is caught the
 * day it is added, and a new informational event shows up as an obviously
 * wrong warning rather than as nothing at all — which is the mistake that gets
 * noticed and fixed. Anything informational must be named here.</p>
 */
public final class ProctoringEventSeverity {

	private ProctoringEventSeverity() {
	}

	/**
	 * Events that are a record, not an accusation.
	 *
	 * <p>Still saved, still shown in the reviewer's proctoring log, and for the
	 * recording ones that is the entire point — "no recording" and "a recording
	 * that did not survive the upload" look identical afterwards, and only one
	 * of them is the candidate's doing. They simply do not count towards
	 * failing the interview.</p>
	 */
	private static final Set<String> INFORMATIONAL = Set.of(
			// The upload audit. A successful upload is self-evidently not a
			// violation; a failed one is usually the network, which is not the
			// candidate's conduct either — and penalising it would mean a
			// candidate on bad wifi fails for it.
			"recording_uploaded",
			"recording_upload_failed",
			"recording_upload_retry",
			"recording_upload_abandoned",
			// The recording audit. What the deployment required, and whether
			// each recording started — the reviewer's answer to "why is there
			// no recording?". A camera that failed to open or dropped out is
			// equipment as often as conduct, so these are recorded, not
			// charged. (A screen share the candidate stops or refuses is
			// still charged, through screen_share_stopped / _denied.)
			"recording_policy",
			"camera_recording_started",
			"camera_recording_not_started",
			"camera_recording_issue",
			"screen_recording_started",
			// More than one display: recorded for the reviewer, never charged.
			"multiple_displays",
			// What the browser did to the microphone. A fact about the device, never conduct.
			"mic_processing",
			// The phone's own recording, reported by the phone.
			"mobile_room_scan",
			"mobile_extra_person",
			"mobile_candidate_absent",
			"mobile_recording_started",
			"mobile_recording_not_started",
			"mobile_recording_issue",
			// Putting a stopped screen share back is the candidate doing the
			// right thing. Stopping it already counted; charging them again for
			// fixing it is a reason not to.
			"screen_share_resumed",
			// Silence on three questions in a row. The commonest cause is a
			// microphone that is not working, which is the candidate's
			// equipment rather than their conduct. It is recorded because a
			// reviewer reading a thin transcript needs to know why.
			"questions_unanswered");

	/**
	 * Whether this event should increment the candidate's warning count.
	 *
	 * @param eventType the client's event type; null or blank counts, because
	 *                  an event that cannot be identified is not one that can
	 *                  be cleared
	 */
	public static boolean countsAsWarning(String eventType) {
		if (eventType == null || eventType.isBlank()) {
			return true;
		}
		return !INFORMATIONAL.contains(eventType.trim().toLowerCase(Locale.ROOT));
	}

	/** Whether this event is a record rather than an accusation. */
	public static boolean isInformational(String eventType) {
		return !countsAsWarning(eventType);
	}
}
