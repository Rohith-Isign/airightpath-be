package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.InterviewResult;
import com.rightpath.enums.InterviewRound;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.service.impl.InterviewServiceImpl;

/**
 * Removing an interview result hides it; it must never destroy it.
 *
 * <p>This is the only admin action in the interview flow that takes a finished
 * result off the screen hiring decisions are made from. Three things have to
 * hold, and none of them are obvious from reading the endpoint:</p>
 *
 * <ul>
 *   <li>A reason is required. Without one the row says a result was removed
 *       and nothing about why, which is the state the audit exists to
 *       prevent.</li>
 *   <li>The evidence survives. The transcript, the recordings, the evaluation
 *       and the proctoring events are all still on the row afterwards.</li>
 *   <li>Removing twice is refused rather than overwriting, so the first
 *       reason and the first remover cannot be replaced by whoever clicked
 *       last.</li>
 * </ul>
 */
class InterviewResultRemovalTest {

	private static final long SCHEDULE_ID = 77L;

	private CandidateInterviewScheduleRepository scheduleRepo;
	private InterviewServiceImpl service;

	@BeforeEach
	void setUp() {
		scheduleRepo = mock(CandidateInterviewScheduleRepository.class);
		service = new InterviewServiceImpl(
				mock(OpenAiService.class),
				scheduleRepo,
				mock(JobApplicationForCandidateRepository.class),
				mock(InterviewReportService.class),
				mock(StorageService.class),
				mock(JobPromptService.class),
				mock(EmailAsyncService.class));

		when(scheduleRepo.save(any(CandidateInterviewSchedule.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private CandidateInterviewSchedule schedule() {
		CandidateInterviewSchedule schedule = new CandidateInterviewSchedule();
		schedule.setId(SCHEDULE_ID);
		schedule.setJobPrefix("FRONTEND-DEV");
		schedule.setEmail("candidate@example.com");
		schedule.setAttemptStatus(AttemptStatus.COMPLETED);
		schedule.setInterviewResult(InterviewResult.PASSED);
		schedule.setRecordReferences("s3://bucket/camera.webm");
		schedule.setScreenRecordReferences("s3://bucket/screen.webm");
		schedule.setEvaluationJson("{\"overallScore\":7.5}");
		return schedule;
	}

	@Test
	void removalStampsWhoWhenAndWhy() {
		CandidateInterviewSchedule row = schedule();
		when(scheduleRepo.findById(SCHEDULE_ID)).thenReturn(Optional.of(row));

		CandidateInterviewSchedule removed = service.deleteResult(SCHEDULE_ID, "  Duplicate sitting  ");

		assertTrue(removed.isDeleted());
		assertNotNull(removed.getDeletedAt());
		// Trimmed, so a reason padded with whitespace does not read as one
		// beginning with blank lines wherever it is displayed.
		assertEquals("Duplicate sitting", removed.getDeleteReason());
		// No request context in a unit test, so the acting user falls back
		// rather than being left null — an audit line is never blank.
		assertEquals("system", removed.getDeletedBy());
	}

	@Test
	void removalKeepsEveryPieceOfEvidence() {
		CandidateInterviewSchedule row = schedule();
		when(scheduleRepo.findById(SCHEDULE_ID)).thenReturn(Optional.of(row));

		CandidateInterviewSchedule removed = service.deleteResult(SCHEDULE_ID, "Test run, not a real candidate");

		assertEquals("s3://bucket/camera.webm", removed.getRecordReferences());
		assertEquals("s3://bucket/screen.webm", removed.getScreenRecordReferences());
		assertEquals("{\"overallScore\":7.5}", removed.getEvaluationJson());
		assertEquals(InterviewResult.PASSED, removed.getInterviewResult());
		assertEquals(AttemptStatus.COMPLETED, removed.getAttemptStatus());
	}

	@Test
	void aBlankReasonIsRefusedAndNothingIsSaved() {
		when(scheduleRepo.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule()));

		assertThrows(IllegalArgumentException.class, () -> service.deleteResult(SCHEDULE_ID, "   "));
		assertThrows(IllegalArgumentException.class, () -> service.deleteResult(SCHEDULE_ID, null));

		verify(scheduleRepo, never()).save(any(CandidateInterviewSchedule.class));
	}

	@Test
	void removingTwiceDoesNotOverwriteTheFirstReason() {
		CandidateInterviewSchedule row = schedule();
		row.setDeletedAt(LocalDateTime.now().minusDays(1));
		row.setDeletedBy("first.admin@example.com");
		row.setDeleteReason("Candidate withdrew");
		when(scheduleRepo.findById(SCHEDULE_ID)).thenReturn(Optional.of(row));

		assertThrows(IllegalStateException.class, () -> service.deleteResult(SCHEDULE_ID, "Something else"));

		assertEquals("Candidate withdrew", row.getDeleteReason());
		assertEquals("first.admin@example.com", row.getDeletedBy());
	}

	@Test
	void restoringClearsTheRemoval() {
		CandidateInterviewSchedule row = schedule();
		row.setDeletedAt(LocalDateTime.now());
		row.setDeletedBy("admin@example.com");
		row.setDeleteReason("Removed in error");
		when(scheduleRepo.findById(SCHEDULE_ID)).thenReturn(Optional.of(row));

		CandidateInterviewSchedule restored = service.restoreResult(SCHEDULE_ID);

		assertFalse(restored.isDeleted());
		assertNull(restored.getDeletedAt());
		assertNull(restored.getDeletedBy());
		assertNull(restored.getDeleteReason());
	}

	@Test
	void restoringSomethingThatWasNeverRemovedIsRefused() {
		when(scheduleRepo.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule()));

		assertThrows(IllegalStateException.class, () -> service.restoreResult(SCHEDULE_ID));
	}

	@Test
	void removedResultsAreLeftOutOfTheListingUnlessAskedFor() {
		CandidateInterviewSchedule live = schedule();
		CandidateInterviewSchedule gone = schedule();
		gone.setId(78L);
		gone.setDeletedAt(LocalDateTime.now());
		gone.setDeleteReason("Duplicate");
		when(scheduleRepo.findAllByJobPrefix("FRONTEND-DEV")).thenReturn(List.of(live, gone));

		List<CandidateInterviewSchedule> defaultView = service.getResults("FRONTEND-DEV", null);
		assertEquals(1, defaultView.size());
		assertEquals(SCHEDULE_ID, defaultView.get(0).getId());

		List<CandidateInterviewSchedule> withRemoved = service.getResults("FRONTEND-DEV", null, true);
		assertEquals(2, withRemoved.size());
	}

	/**
	 * A round filter and the removal filter have to compose. Applying one and
	 * forgetting the other is how a removed result reappears the moment
	 * somebody narrows the list to a single round.
	 */
	@Test
	void theRoundFilterStillExcludesRemovedResults() {
		CandidateInterviewSchedule live = schedule();
		live.setRound(InterviewRound.L2_TECHNICAL);
		CandidateInterviewSchedule gone = schedule();
		gone.setId(79L);
		gone.setRound(InterviewRound.L2_TECHNICAL);
		gone.setDeletedAt(LocalDateTime.now());
		when(scheduleRepo.findAllByJobPrefix("FRONTEND-DEV")).thenReturn(List.of(live, gone));

		List<CandidateInterviewSchedule> filtered = service.getResults("FRONTEND-DEV", InterviewRound.L2_TECHNICAL);

		assertEquals(1, filtered.size());
		assertEquals(SCHEDULE_ID, filtered.get(0).getId());
	}
}
