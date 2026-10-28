package com.rightpath.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.entity.InterviewMobilePairing;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.repository.CandidateInterviewScheduleRepository;

/**
 * A phone that has gone quiet is noted for the reviewer; it never ends the interview.
 *
 * <p>With no live connection to the phone there is no disconnect event to react to; the phone's check-ins are the
 * only sign it is there. These pin down what the sweep does with a pairing that has stopped checking in.</p>
 */
class MobileDisconnectWatcherTest {

	private MobilePairingService pairings;
	private CandidateInterviewScheduleRepository schedules;
	private MobileDisconnectWatcher watcher;
	private InterviewMobilePairing pairing;

	@BeforeEach
	void setUp() {
		pairings = mock(MobilePairingService.class);
		schedules = mock(CandidateInterviewScheduleRepository.class);
		watcher = new MobileDisconnectWatcher(pairings, schedules);
		ReflectionTestUtils.setField(watcher, "graceSeconds", 120L);
		pairing = InterviewMobilePairing.builder().token("t").interviewScheduleId(9L).build();
		when(pairings.silentFor(120L)).thenReturn(List.of(pairing));
	}

	@Test
	void aSilentPhoneIsNotedButTheInterviewIsNotEnded() {
		when(schedules.findAttemptStatusById(9L)).thenReturn(Optional.of(AttemptStatus.IN_PROGRESS));

		watcher.sweep();

		verify(pairings).recordEvent(eq(pairing), eq("mobile_disconnected"), any());
	}

	@Test
	void aLongSilenceIsFiledOnce() {
		when(schedules.findAttemptStatusById(9L)).thenReturn(Optional.of(AttemptStatus.IN_PROGRESS));

		watcher.sweep();
		watcher.sweep();

		verify(pairings, times(1)).recordEvent(eq(pairing), eq("mobile_disconnected"), any());
	}

	@Test
	void anInterviewAlreadyOverIsLeftAlone() {
		when(schedules.findAttemptStatusById(9L)).thenReturn(Optional.of(AttemptStatus.COMPLETED));

		watcher.sweep();

		verify(pairings, never()).recordEvent(any(), any(), any());
	}

	@Test
	void anInterviewNotYetStartedIsLeftAlone() {
		when(schedules.findAttemptStatusById(9L)).thenReturn(Optional.of(AttemptStatus.NOT_ATTEMPTED));

		watcher.sweep();

		verify(pairings, never()).recordEvent(any(), any(), any());
	}
}
