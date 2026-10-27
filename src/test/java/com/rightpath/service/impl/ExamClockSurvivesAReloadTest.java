package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.entity.Assessment;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.ResultRepository;
import com.rightpath.service.EmailService;
import com.rightpath.service.StorageService;

/**
 * Reloading a paper must not hand the candidate more time.
 *
 * <p>The exam clock was a countdown started at the paper's full length every
 * time the page loaded. Refreshing restarted it, so a candidate could reload
 * at the ninety-minute mark and get the whole paper's time over again — and
 * there was no way to tell from the result that they had.</p>
 *
 * <p>The fix is to count down to a fixed instant instead, and the instant has
 * to come from the server: {@code examStartedAt} is stamped on first
 * attendance and never moved. These tests pin that it is stamped, that a
 * second attendance does not move it, and that it is handed back — the last
 * being the part the browser needs and the part that was missing.</p>
 */
class ExamClockSurvivesAReloadTest {

	private static final String CANDIDATE = "candidate@example.com";
	private static final String JOB_PREFIX = "FIXT-CLOCK-2026-001";
	private static final long ASSESSMENT_ID = 7L;

	private AssessmentRepository assessments;
	private AssessmentServiceImpl service;

	@BeforeEach
	void setUp() {
		assessments = mock(AssessmentRepository.class);
		JobApplicationForCandidateRepository applications = mock(JobApplicationForCandidateRepository.class);

		service = new AssessmentServiceImpl(
				assessments,
				mock(ResultRepository.class),
				mock(EmailService.class),
				applications,
				mock(StorageService.class));
		ReflectionTestUtils.setField(service, "examPrefix", "exam");

		// A non-empty list: the service refuses outright without one, and that
		// guard is not what is under test here.
		when(applications.findByJobPrefixAndEmail(anyString(), anyString()))
				.thenReturn(List.of(new JobApplicationForCandidate()));
		when(assessments.save(org.mockito.ArgumentMatchers.any(Assessment.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private Assessment paper() {
		Assessment assessment = new Assessment();
		assessment.setId(ASSESSMENT_ID);
		assessment.setCandidateEmail(CANDIDATE);
		assessment.setJobPrefix(JOB_PREFIX);
		when(assessments.findById(ASSESSMENT_ID)).thenReturn(Optional.of(assessment));
		return assessment;
	}

	@Test
	void openingThePaperStampsWhenItStarted() {
		Assessment assessment = paper();
		LocalDateTime before = LocalDateTime.now().minusSeconds(1);

		LocalDateTime startedAt = service.markExamAsAttended(CANDIDATE, ASSESSMENT_ID);

		assertNotNull(startedAt, "the browser needs this to anchor its clock");
		assertNotNull(assessment.getExamStartedAt());
		assertTrue(assessment.isExamAttended());
		// Stamped in UTC, which is how the rest of the schema stores instants.
		assertTrue(startedAt.isAfter(before.minusHours(24)), "a plausible instant, not a default");
	}

	/** The whole point: a reload reports attendance again and moves nothing. */
	@Test
	void reloadingPartWayThroughDoesNotRestartTheClock() {
		Assessment assessment = paper();
		LocalDateTime ninetyMinutesAgo = LocalDateTime.now().minusMinutes(90);
		assessment.setExamStartedAt(ninetyMinutesAgo);
		assessment.setExamAttended(true);

		LocalDateTime startedAt = service.markExamAsAttended(CANDIDATE, ASSESSMENT_ID);

		assertEquals(ninetyMinutesAgo, startedAt,
				"a candidate who refreshed at 90 minutes must not be treated as having just begun");
		assertEquals(ninetyMinutesAgo, assessment.getExamStartedAt());
	}

	/** And it is the same instant each time, however many times they reload. */
	@Test
	void everyLaterAttendanceReturnsTheSameInstant() {
		paper();

		LocalDateTime first = service.markExamAsAttended(CANDIDATE, ASSESSMENT_ID);
		LocalDateTime second = service.markExamAsAttended(CANDIDATE, ASSESSMENT_ID);
		LocalDateTime third = service.markExamAsAttended(CANDIDATE, ASSESSMENT_ID);

		assertEquals(first, second);
		assertSame(second, third, "the stored stamp, not a fresh reading of the clock");
	}
}
