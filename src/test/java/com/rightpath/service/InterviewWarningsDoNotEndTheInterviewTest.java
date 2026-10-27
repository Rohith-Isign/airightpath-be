package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.InterviewResult;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.repository.VoiceConversationEntryRepository;
import com.rightpath.service.impl.VoiceInterviewServiceImpl;
import com.rightpath.util.InterviewReplyParser;
import com.rightpath.util.PromptInjectionGuard;

/**
 * Proctoring warnings do not end an L2 or L3 interview.
 *
 * <p>They did. At five the schedule was set to {@code COMPLETED} /
 * {@code FAILED} with {@code PROCTORING_VIOLATION} and the candidate was cut
 * off — a hiring outcome decided by a counter, from signals that are not good
 * enough to carry it. A candidate who looked away from their camera five
 * times, or whose face the detector lost while they were thinking, failed.</p>
 *
 * <p>The candidate's own screen made it worse: it showed the count against a
 * ceiling of 999999, so the number that actually mattered looked like nothing
 * right up until the interview ended.</p>
 *
 * <p>Warnings are still counted and still recorded. Past the threshold the
 * interview is flagged for a person to look at, which is who should be
 * deciding.</p>
 */
class InterviewWarningsDoNotEndTheInterviewTest {

	private static final long SCHEDULE_ID = 31L;
	private static final int MAX_WARNINGS = 5;

	private CandidateInterviewScheduleRepository scheduleRepo;
	private VoiceInterviewServiceImpl service;

	@BeforeEach
	void setUp() {
		scheduleRepo = mock(CandidateInterviewScheduleRepository.class);

		service = new VoiceInterviewServiceImpl(
				scheduleRepo,
				mock(VoiceConversationEntryRepository.class),
				mock(OpenAiStreamingService.class),
				mock(InterviewContextService.class),
				mock(TextToSpeechService.class),
				mock(ToneAnalysisService.class),
				mock(InterviewEvaluationService.class),
				mock(CandidatePerformanceAnalyzer.class),
				mock(SimpMessagingTemplate.class),
				mock(TransactionTemplate.class),
				mock(InterviewConductPolicy.class),
				mock(InterviewReplyParser.class),
				mock(InterviewTopicCoverage.class),
				mock(PromptInjectionGuard.class),
				mock(InterviewTemplateService.class),
				mock(UsersRepository.class));

		ReflectionTestUtils.setField(service, "maxWarnings", MAX_WARNINGS);
		ReflectionTestUtils.setField(service, "terminateOnMaxWarnings", false);

		when(scheduleRepo.save(any(CandidateInterviewSchedule.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private CandidateInterviewSchedule live() {
		CandidateInterviewSchedule schedule = new CandidateInterviewSchedule();
		schedule.setId(SCHEDULE_ID);
		schedule.setAttemptStatus(AttemptStatus.IN_PROGRESS);
		when(scheduleRepo.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));
		return schedule;
	}

	/** Warning after warning, well past the threshold, and it keeps running. */
	@Test
	void theInterviewSurvivesEveryWarning() {
		CandidateInterviewSchedule schedule = live();

		for (int i = 1; i <= MAX_WARNINGS * 2; i++) {
			assertFalse(service.handleWarning(SCHEDULE_ID),
					"warning " + i + " should not have ended the interview");
		}

		assertEquals(AttemptStatus.IN_PROGRESS, schedule.getAttemptStatus());
		assertNull(schedule.getCompletionReason());
		assertNull(schedule.getEndedAt());
	}

	/** The count is still kept — it is the reason a reviewer looks. */
	@Test
	void warningsAreStillCounted() {
		CandidateInterviewSchedule schedule = live();

		service.handleWarning(SCHEDULE_ID);
		service.handleWarning(SCHEDULE_ID);

		assertEquals(2, schedule.getWarningCount());
	}

	/** Below the threshold nothing is flagged; a stray warning is not a case. */
	@Test
	void aFewWarningsDoNotFlagTheInterview() {
		CandidateInterviewSchedule schedule = live();

		service.handleWarning(SCHEDULE_ID);
		service.handleWarning(SCHEDULE_ID);

		assertFalse(schedule.isNeedsHumanReview());
	}

	/** Past it, a person is asked to look — instead of the machine deciding. */
	@Test
	void crossingTheThresholdFlagsItForAPerson() {
		CandidateInterviewSchedule schedule = live();

		for (int i = 0; i < MAX_WARNINGS; i++) {
			service.handleWarning(SCHEDULE_ID);
		}

		assertTrue(schedule.isNeedsHumanReview());
		// Flagged, not failed. These are the two fields a hiring decision
		// reads, and neither was touched.
		assertEquals(AttemptStatus.IN_PROGRESS, schedule.getAttemptStatus());
		assertFalse(InterviewResult.FAILED.equals(schedule.getInterviewResult()));
	}

	/**
	 * The old behaviour is still reachable, because somewhere running a
	 * high-stakes certification may genuinely want it. It has to be asked for.
	 */
	@Test
	void terminationStillWorksWhenItIsSwitchedOn() {
		ReflectionTestUtils.setField(service, "terminateOnMaxWarnings", true);
		CandidateInterviewSchedule schedule = live();

		for (int i = 1; i < MAX_WARNINGS; i++) {
			assertFalse(service.handleWarning(SCHEDULE_ID), "warning " + i + " is below the limit");
		}
		assertTrue(service.handleWarning(SCHEDULE_ID), "the limit should end the interview");

		assertEquals(AttemptStatus.COMPLETED, schedule.getAttemptStatus());
		assertEquals(InterviewResult.FAILED, schedule.getInterviewResult());
	}
}
