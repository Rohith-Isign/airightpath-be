package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.dto.AssignAssessmentDto;
import com.rightpath.dto.AssignmentReportDTO;
import com.rightpath.entity.Assessment;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.ResultRepository;
import com.rightpath.service.EmailService;
import com.rightpath.service.StorageService;

/**
 * Assigning an exam and telling the candidate about it are separate things that
 * fail separately.
 *
 * <p>The exam is created here; the email leaves through a rate-limited third
 * party. Letting a 451 from that host escape aborted the whole request — the
 * transaction discarded every candidate, including the ones already emailed, and
 * the recruiter got a 500 naming nobody. What must survive a mail outage is the
 * assignment; what must not be lost is the fact that a candidate was never
 * told.</p>
 */
class AssignmentSurvivesMailOutageTest {

	private static final String JOB_PREFIX = "RP-AIML-FRESHERS-01-018";
	private static final String FIRST = "first@example.com";
	private static final String SECOND = "second@example.com";
	private static final String THIRD = "third@example.com";

	private AssessmentRepository assessments;
	private EmailService email;
	private JobApplicationForCandidateRepository applications;
	private AssessmentServiceImpl service;

	@BeforeEach
	void setUp() {
		assessments = mock(AssessmentRepository.class);
		email = mock(EmailService.class);
		applications = mock(JobApplicationForCandidateRepository.class);

		service = new AssessmentServiceImpl(assessments, mock(ResultRepository.class), email, applications,
				mock(StorageService.class));
		ReflectionTestUtils.setField(service, "examPrefix", "exam");

		when(applications.findByJobPrefixAndEmail(anyString(), anyString())).thenReturn(List.of());
	}

	@Test
	void aRateLimitedHostDoesNotThrowAwayTheWholeAssignment() {
		// Exactly what the live log showed: 451 ... Ratelimit "hostinger_out_ratelimit".
		rateLimit(SECOND);

		AssignmentReportDTO report = service.assignAssessment(assignment(FIRST, SECOND, THIRD), JOB_PREFIX);

		// Every candidate keeps their exam — including the one whose email bounced,
		// and the one queued behind them who never used to be reached at all.
		assertEquals(3, report.getAssignedCount());
		verify(assessments, times(3)).save(any(Assessment.class));

		assertEquals(List.of(FIRST, THIRD), report.getNotified());
		assertEquals(1, report.getNotNotifiedCount());
		assertEquals(SECOND, report.getNotNotified().get(0).get("email"));
		assertTrue(report.getNotNotified().get(0).get("reason").contains("Ratelimit"),
				report.getNotNotified().get(0).get("reason"));
	}

	@Test
	void theRecruiterIsToldWhoToChaseAndHow() {
		rateLimit(SECOND);

		AssignmentReportDTO report = service.assignAssessment(assignment(FIRST, SECOND), JOB_PREFIX);

		assertFalse(report.allNotified());
		// "Assigned successfully" would be a lie by omission: the exam is waiting and
		// the candidate has no idea. The message names the recovery.
		assertTrue(report.getMessage().contains("could not be emailed"), report.getMessage());
		assertTrue(report.getMessage().contains("Send Exam Link"), report.getMessage());
	}

	@Test
	void aCandidateWhoWasNotEmailedIsNotMarkedAsHavingBeenSentTheLink() {
		JobApplicationForCandidate application = new JobApplicationForCandidate();
		when(applications.findByJobPrefixAndEmail(JOB_PREFIX, SECOND)).thenReturn(List.of(application));
		rateLimit(SECOND);

		service.assignAssessment(assignment(SECOND), JOB_PREFIX);

		// EXAM_SENT reads "Exam Link Sent". Setting it here would leave a candidate
		// nobody thinks to chase, and the column would be recording something that
		// did not happen.
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
	}

	@Test
	void theWrappedMessagingExceptionShapeIsToleratedToo() {
		// EmailServiceImpl wraps MessagingException in a plain RuntimeException, so a
		// delivery failure does not always arrive as a MailException.
		doThrow(new RuntimeException("Failed to send email",
				new jakarta.mail.MessagingException("relay closed the connection")))
				.when(email).sendExamLink(eq(FIRST), any(), any(), anyString());

		AssignmentReportDTO report = service.assignAssessment(assignment(FIRST), JOB_PREFIX);

		assertEquals(1, report.getNotNotifiedCount());
		assertEquals("relay closed the connection", report.getNotNotified().get(0).get("reason"));
	}

	@Test
	void afaultThatIsNotADeliveryProblemStillFails() {
		// Tolerating "the email didn't send" must not become tolerating everything:
		// a bug in the notification path has to stay visible.
		doThrow(new IllegalStateException("no application row for candidate"))
				.when(email).sendExamLink(eq(FIRST), any(), any(), anyString());

		assertThrows(IllegalStateException.class, () -> service.assignAssessment(assignment(FIRST), JOB_PREFIX));
	}

	private void rateLimit(String candidate) {
		doNothing().when(email).sendExamLink(anyString(), any(), any(), anyString());
		doThrow(new MailSendException("Failed messages: 451 4.7.1 Ratelimit \"hostinger_out_ratelimit\" exceeded"))
				.when(email).sendExamLink(eq(candidate), any(), any(), anyString());
	}

	private static AssignAssessmentDto assignment(String... candidates) {
		AssignAssessmentDto dto = new AssignAssessmentDto();
		dto.setCandidateEmails(List.of(candidates));
		dto.setStartTime(LocalDateTime.parse("2026-08-20T10:00"));
		dto.setDeadline(LocalDateTime.parse("2026-08-20T11:00"));
		dto.setUploadedBy("recruiter@example.com");
		dto.setJobPrefix(JOB_PREFIX);
		dto.setAptitudeQuestionPaper(new MockMultipartFile("aptitudeQuestionPaper", "apt.json",
				"application/json", "[{\"question\":\"1 + 1?\"}]".getBytes(StandardCharsets.UTF_8)));
		return dto;
	}
}
