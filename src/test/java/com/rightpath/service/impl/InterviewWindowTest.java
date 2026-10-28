package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.util.BusinessSchedule;

/**
 * When an interview may be started.
 *
 * <p>A recruiter in India books "11:23 to 18:00". The server runs in UTC, where it is 05:57. Read against the
 * server's clock the slot had not opened for hours and the candidate, sitting at 11:27 their time, was told so.
 * These pin the comparison to the business zone the slot was written in.</p>
 */
class InterviewWindowTest {

	private static CandidateInterviewSchedule window(String opens, String closes) {
		CandidateInterviewSchedule s = new CandidateInterviewSchedule();
		s.setAssignedAt(LocalDateTime.parse(opens));
		s.setDeadlineTime(LocalDateTime.parse(closes));
		return s;
	}

	@Test
	void aSlotThatOpenedAMinutesAgoInTheBusinessZoneCanBeStarted() {
		// 11:27 in India, with the slot opening at 11:23.
		assertDoesNotThrow(() -> VoiceInterviewServiceImpl.requireWithinWindow(
				window("2026-10-28T11:23", "2026-10-28T18:00"), LocalDateTime.parse("2026-10-28T11:27")));
	}

	@Test
	void theSameSlotReadAgainstAUtcClockWouldHaveBeenRefused() {
		// What the old comparison saw: 05:57, the same moment on a UTC server.
		var failure = assertThrows(IllegalStateException.class, () -> VoiceInterviewServiceImpl.requireWithinWindow(
				window("2026-10-28T11:23", "2026-10-28T18:00"), LocalDateTime.parse("2026-10-28T05:57")));
		assertTrue(failure.getMessage().contains("not opened yet"), failure.getMessage());
	}

	@Test
	void theBusinessZoneClockIsTheOneInKolkata() {
		LocalDateTime kolkata = new BusinessSchedule("Asia/Kolkata").now();
		LocalDateTime utc = LocalDateTime.now(java.time.ZoneOffset.UTC);

		long hoursApart = java.time.Duration.between(utc, kolkata).toMinutes();
		assertTrue(Math.abs(hoursApart - 330) <= 2, "expected about 5h30 ahead of UTC, was " + hoursApart + " min");
	}

	@Test
	void aSlotAfterItsEndIsRefused() {
		var failure = assertThrows(IllegalStateException.class, () -> VoiceInterviewServiceImpl.requireWithinWindow(
				window("2026-10-28T09:00", "2026-10-28T10:00"), LocalDateTime.parse("2026-10-28T11:27")));
		assertTrue(failure.getMessage().contains("has passed"), failure.getMessage());
	}

	@Test
	void aMinuteEarlyIsAllowedForClockDifferences() {
		assertDoesNotThrow(() -> VoiceInterviewServiceImpl.requireWithinWindow(
				window("2026-10-28T11:28", "2026-10-28T18:00"), LocalDateTime.parse("2026-10-28T11:27")));
	}
}
