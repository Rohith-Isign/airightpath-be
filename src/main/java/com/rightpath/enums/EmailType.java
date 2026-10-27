package com.rightpath.enums;

/**
 * One value per candidate-facing mail. Each maps to a template in
 * {@code EmailServiceImpl.compose(...)}.
 */
public enum EmailType {

	/** Application received — sent the moment a candidate applies. */
	APPLICATION_SUCCESS,
	/** Test slot proposed; the candidate is asked to confirm attendance. */
	ACKNOWLEDGEMENT,
	/** The candidate confirmed; slot is booked. */
	ACKNOWLEDGEMENT_CONFIRMATION,
	/** Reminder ahead of the booked test slot. */
	RECONFIRMATION,
	REJECTION,
	WRITTEN_TEST_SUCCESS,
	WRITTEN_TEST_FAILURE,
	SHORTLIST_NOTIFICATION,
	/** Online test window is open — carries the link and the ground rules. */
	EXAM_SCHEDULE,
	/** Interview round assigned, with the window to complete it. */
	INTERVIEW_SCHEDULE,
	OTP,
	PASSWORD_UPDATED,
	EXAM_SUBMISSION,
	CODING_EXAM_SUBMISSION,
	REGISTRATION_SUCCESS

}
