package com.rightpath.service.impl;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.dto.ScreeningResponseDTO;
import com.rightpath.dto.ScreeningResultDTO;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.Users;
import com.rightpath.enums.ApplicationStatus;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.JobPostRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.service.ResumeService;
import com.rightpath.util.SynonymLoader;

/**
 * Scoped ATS screening, and the way back from a rejection.
 *
 * <p>These are the three guarantees the ATS console is built on: screening a subset
 * touches only that subset, it can never walk a candidate back out of an exam or
 * interview, and a recruiter always has a route to overrule a rejection.</p>
 */
class ScopedScreeningTest {

	private static final String PREFIX = "FIXT-SCREEN-2026-001";
	private static final String STRONG_RESUME = "java spring boot sql";
	private static final String WEAK_RESUME = "cooking gardening";

	private JobApplicationForCandidateRepository applications;
	private JobPostRepository jobPosts;
	private ResumeService resumes;
	private JobApplicationForCandidateServiceImpl service;
	private JobPost job;

	@BeforeEach
	void setUp() {
		applications = mock(JobApplicationForCandidateRepository.class);
		jobPosts = mock(JobPostRepository.class);
		resumes = mock(ResumeService.class);
		UsersRepository users = mock(UsersRepository.class);
		SynonymLoader synonyms = mock(SynonymLoader.class);

		service = new JobApplicationForCandidateServiceImpl(applications, users, jobPosts, "https://example.test");
		ReflectionTestUtils.setField(service, "resumeService", resumes);
		ReflectionTestUtils.setField(service, "synonymLoader", synonyms);
		ReflectionTestUtils.setField(service, "atsThreshold", 60.0);

		when(synonyms.getSynonymMap()).thenReturn(Map.of());

		job = JobPost.builder().id(1L).jobPrefix(PREFIX).jobTitle("Backend Engineer")
				.keySkills("Java, Spring, SQL").build();
		when(jobPosts.findByJobPrefix(PREFIX)).thenReturn(Optional.of(job));
	}

	// ---------------------------------------------------------------- Q3: no data loss

	@Test
	void screeningNeverWalksACandidateBackOutOfTheExamOrInterviewStage() {
		JobApplicationForCandidate examSent = applicant("exam@example.test", ApplicationStatus.EXAM_SENT, WEAK_RESUME);
		JobApplicationForCandidate interviewing =
				applicant("interview@example.test", ApplicationStatus.INTERVIEW_SCHEDULED, WEAK_RESUME);
		givenApplicants(examSent, interviewing);

		ScreeningResponseDTO response = service.screenCandidates(PREFIX, null);

		assertEquals(ApplicationStatus.EXAM_SENT, examSent.getStatus(), "exam candidate must be untouched");
		assertEquals(ApplicationStatus.INTERVIEW_SCHEDULED, interviewing.getStatus(),
				"interviewing candidate must be untouched");
		verify(applications, never()).save(any(JobApplicationForCandidate.class));

		assertEquals(0, response.screenedCount());
		assertEquals(2, response.skippedCount());
		// The recruiter is told why, rather than left to notice nothing changed.
		assertTrue(result(response, "exam@example.test").reason().contains("undo pipeline progress"),
				"got: " + result(response, "exam@example.test").reason());
	}

	@Test
	void aWholeJobScreenStillEvaluatesEveryoneStillInTheScreeningPhase() {
		JobApplicationForCandidate fresh = applicant("fresh@example.test", ApplicationStatus.APPLIED, STRONG_RESUME);
		JobApplicationForCandidate weak = applicant("weak@example.test", ApplicationStatus.APPLIED, WEAK_RESUME);
		JobApplicationForCandidate interviewing =
				applicant("interview@example.test", ApplicationStatus.INTERVIEW_SCHEDULED, STRONG_RESUME);
		givenApplicants(fresh, weak, interviewing);

		ScreeningResponseDTO response = service.screenCandidates(PREFIX, List.of());

		assertEquals(ScreeningResponseDTO.SCOPE_ALL, response.scope());
		assertEquals(ApplicationStatus.SHORTLISTED, fresh.getStatus());
		assertEquals(ApplicationStatus.REJECTED, weak.getStatus());
		assertEquals(ApplicationStatus.INTERVIEW_SCHEDULED, interviewing.getStatus());
		assertEquals(2, response.screenedCount());
		assertEquals(1, response.shortlistedCount());
		assertEquals(1, response.rejectedCount());
	}

	// ------------------------------------------------------------- scoped screening

	@Test
	void screeningASubsetLeavesEveryOtherApplicantAlone() {
		JobApplicationForCandidate picked = applicant("picked@example.test", ApplicationStatus.APPLIED, STRONG_RESUME);
		JobApplicationForCandidate bystander =
				applicant("bystander@example.test", ApplicationStatus.APPLIED, WEAK_RESUME);
		givenApplicants(picked, bystander);

		ScreeningResponseDTO response = service.screenCandidates(PREFIX, List.of("picked@example.test"));

		assertEquals(ScreeningResponseDTO.SCOPE_SELECTED, response.scope());
		assertEquals(ApplicationStatus.SHORTLISTED, picked.getStatus());
		assertEquals(ApplicationStatus.APPLIED, bystander.getStatus(), "unselected applicant must not be re-scored");
		verify(applications).save(picked);
		verify(applications, never()).save(bystander);
		assertEquals(1, response.results().size());
	}

	@Test
	void selectionMatchesEmailsRegardlessOfCaseOrSurroundingSpace() {
		JobApplicationForCandidate picked = applicant("Picked@Example.test", ApplicationStatus.APPLIED, STRONG_RESUME);
		givenApplicants(picked);

		ScreeningResponseDTO response = service.screenCandidates(PREFIX, List.of("  picked@example.TEST  "));

		assertEquals(1, response.screenedCount(), "case/whitespace must not turn a real row into 'not found'");
		assertEquals(ApplicationStatus.SHORTLISTED, picked.getStatus());
	}

	@Test
	void anEmailWithNoApplicationIsReportedRatherThanSilentlyDropped() {
		givenApplicants(applicant("real@example.test", ApplicationStatus.APPLIED, STRONG_RESUME));

		ScreeningResponseDTO response = service.screenCandidates(PREFIX, List.of("ghost@example.test"));

		assertEquals(1, response.notFoundCount());
		assertEquals(0, response.screenedCount());
		assertNull(result(response, "ghost@example.test").status());
	}

	@Test
	void aRepeatedEmailIsScreenedOnce() {
		JobApplicationForCandidate picked = applicant("dupe@example.test", ApplicationStatus.APPLIED, STRONG_RESUME);
		givenApplicants(picked);

		ScreeningResponseDTO response =
				service.screenCandidates(PREFIX, List.of("dupe@example.test", "DUPE@example.test"));

		assertEquals(1, response.results().size(), "a duplicate selection must not double-score the candidate");
	}

	// ---------------------------------------------------- Q2: re-screening a rejection

	@Test
	void rescreeningReopensAnAtsRejectionWhoseResumeNowMatches() {
		JobApplicationForCandidate rejected =
				applicant("second-chance@example.test", ApplicationStatus.REJECTED, STRONG_RESUME);
		rejected.setAtsScanStatus("Screening Completed");
		givenApplicants(rejected);

		ScreeningResponseDTO response = service.screenCandidates(PREFIX, List.of("second-chance@example.test"));

		assertEquals(ApplicationStatus.SHORTLISTED, rejected.getStatus(),
				"an ATS rejection must be reversible by re-screening");
		assertEquals(1, response.shortlistedCount());
	}

	@Test
	void rescreeningDoesNotResurrectACandidateWhoseRejectionWasFinalised() {
		JobApplicationForCandidate rejected =
				applicant("mailed@example.test", ApplicationStatus.REJECTED, STRONG_RESUME);
		rejected.setAtsScanStatus("Screening Completed");
		rejected.setRejectionStatus("Rejection Mail Sent");
		givenApplicants(rejected);

		ScreeningResponseDTO response = service.screenCandidates(PREFIX, List.of("mailed@example.test"));

		assertEquals(ApplicationStatus.REJECTED, rejected.getStatus());
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
		// The reason has to point at the way out, or the recruiter is stuck.
		assertTrue(result(response, "mailed@example.test").reason().contains("override"),
				"got: " + result(response, "mailed@example.test").reason());
	}

	@Test
	void aReferralShortlistIsNeverRescoredByAts() {
		JobApplicationForCandidate referral =
				applicant("referred@example.test", ApplicationStatus.SHORTLISTED, WEAK_RESUME);
		// No atsScanStatus: this candidate was shortlisted at apply time, never screened.
		givenApplicants(referral);

		ScreeningResponseDTO response = service.screenCandidates(PREFIX, List.of("referred@example.test"));

		assertEquals(ApplicationStatus.SHORTLISTED, referral.getStatus());
		assertEquals(1, response.skippedCount());
	}

	@Test
	void aSkippedCandidateStillCarriesTheScoreTheyWouldHaveGot() {
		JobApplicationForCandidate interviewing =
				applicant("interview@example.test", ApplicationStatus.INTERVIEW_SCHEDULED, STRONG_RESUME);
		givenApplicants(interviewing);

		ScreeningResultDTO skipped =
				result(service.screenCandidates(PREFIX, null), "interview@example.test");

		assertFalse(skipped.screened());
		assertNotNull(skipped.matchPercent(), "the UI still shows a match% for rows it may not screen");
		assertEquals(100.0, skipped.matchPercent(), 0.01);
	}

	// -------------------------------------------------- Q1: shortlist anyway (override)

	@Test
	void shortlistAnywayReopensARejection() {
		JobApplicationForCandidate rejected =
				applicant("overrule@example.test", ApplicationStatus.REJECTED, WEAK_RESUME);
		rejected.setAtsScanStatus("Screening Completed");
		rejected.setRejectionStatus("Rejection Mail Sent");
		when(applications.findByJobPrefixAndEmail(PREFIX, "overrule@example.test")).thenReturn(List.of(rejected));

		service.shortlistCandidateWithoutAts(PREFIX, "overrule@example.test", true);

		assertEquals(ApplicationStatus.SHORTLISTED, rejected.getStatus());
		assertEquals("Shortlisted (Override)", rejected.getShortlistStatus());
		// Cleared, so the candidate is screenable again rather than shortlisted-but-frozen.
		assertNull(rejected.getRejectionStatus());
		verify(applications).save(rejected);
	}

	@Test
	void aRejectionOverriddenThisWayCanBeScreenedAgain() {
		JobApplicationForCandidate rejected =
				applicant("overrule@example.test", ApplicationStatus.REJECTED, WEAK_RESUME);
		rejected.setAtsScanStatus("Screening Completed");
		rejected.setRejectionStatus("Rejection Mail Sent");
		when(applications.findByJobPrefixAndEmail(PREFIX, "overrule@example.test")).thenReturn(List.of(rejected));
		givenApplicants(rejected);

		service.shortlistCandidateWithoutAts(PREFIX, "overrule@example.test", true);
		ScreeningResponseDTO response = service.screenCandidates(PREFIX, List.of("overrule@example.test"));

		assertEquals(1, response.screenedCount(), "override must return the row to the screening phase");
	}

	@Test
	void withoutTheOverrideARejectionIsStillRefused() {
		JobApplicationForCandidate rejected =
				applicant("closed@example.test", ApplicationStatus.REJECTED, STRONG_RESUME);
		when(applications.findByJobPrefixAndEmail(PREFIX, "closed@example.test")).thenReturn(List.of(rejected));

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> service.shortlistCandidateWithoutAts(PREFIX, "closed@example.test"));

		assertTrue(error.getMessage().contains("REJECTED"), "got: " + error.getMessage());
		assertEquals(ApplicationStatus.REJECTED, rejected.getStatus());
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
	}

	@Test
	void theOverrideOnlyReopensRejectionsAndNotFinalisedSelections() {
		JobApplicationForCandidate selected =
				applicant("hired@example.test", ApplicationStatus.SELECTED, STRONG_RESUME);
		when(applications.findByJobPrefixAndEmail(PREFIX, "hired@example.test")).thenReturn(List.of(selected));

		assertThrows(IllegalStateException.class,
				() -> service.shortlistCandidateWithoutAts(PREFIX, "hired@example.test", true));

		assertEquals(ApplicationStatus.SELECTED, selected.getStatus());
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
	}

	@Test
	void theOverrideDoesNotChangeTheOrdinaryAppliedShortlist() {
		JobApplicationForCandidate applied = applicant("new@example.test", ApplicationStatus.APPLIED, STRONG_RESUME);
		when(applications.findByJobPrefixAndEmail(PREFIX, "new@example.test")).thenReturn(List.of(applied));

		service.shortlistCandidateWithoutAts(PREFIX, "new@example.test", true);

		assertEquals(ApplicationStatus.SHORTLISTED, applied.getStatus());
		assertEquals("Shortlisted", applied.getShortlistStatus(), "not an override — nothing was reopened");
	}

	// ------------------------------------------------------------ read paths are read-only

	@Test
	void listingShortlistedCandidatesDoesNotRescreenTheJob() {
		JobApplicationForCandidate shortlisted =
				applicant("in@example.test", ApplicationStatus.SHORTLISTED, WEAK_RESUME);
		shortlisted.setAtsScanStatus("Screening Completed");
		JobApplicationForCandidate rejected = applicant("out@example.test", ApplicationStatus.REJECTED, WEAK_RESUME);
		givenApplicants(shortlisted, rejected);

		assertEquals(1, service.getShortlistedCandidatesByPrefix(PREFIX).size());

		// Without this, merely opening the tab would demote the shortlisted candidate
		// (their resume scores below threshold) — a status change nobody asked for.
		assertEquals(ApplicationStatus.SHORTLISTED, shortlisted.getStatus());
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
	}

	@Test
	void listingRejectedCandidatesDoesNotRescreenTheJob() {
		JobApplicationForCandidate rejected = applicant("out@example.test", ApplicationStatus.REJECTED, STRONG_RESUME);
		rejected.setAtsScanStatus("Screening Completed");
		givenApplicants(rejected);

		assertEquals(1, service.getRejectedCandidatesByPrefix(PREFIX).size());

		assertEquals(ApplicationStatus.REJECTED, rejected.getStatus());
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
	}

	@Test
	void aJobWithNoKeySkillsSaysSoInsteadOfFailingMidRun() {
		job.setKeySkills("   ");
		givenApplicants(applicant("someone@example.test", ApplicationStatus.APPLIED, STRONG_RESUME));

		IllegalStateException error =
				assertThrows(IllegalStateException.class, () -> service.screenCandidates(PREFIX, null));

		assertTrue(error.getMessage().contains("key skills"), "got: " + error.getMessage());
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
	}

	// ------------------------------------------------------------------------ fixtures

	private void givenApplicants(JobApplicationForCandidate... applicants) {
		when(applications.findByJobPost(job)).thenReturn(List.of(applicants));
	}

	private JobApplicationForCandidate applicant(String email, ApplicationStatus status, String resumeText) {
		Users user = new Users();
		user.setEmail(email);

		byte[] resumeData = resumeText.getBytes();
		when(resumes.extractText(resumeData)).thenReturn(resumeText);

		return JobApplicationForCandidate.builder()
				.id((long) email.hashCode())
				.firstName("Test")
				.lastName("Candidate")
				.user(user)
				.jobPost(job)
				.status(status)
				.resumeData(resumeData)
				.build();
	}

	private static ScreeningResultDTO result(ScreeningResponseDTO response, String email) {
		return response.results().stream()
				.filter(r -> email.equalsIgnoreCase(r.email()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no result for " + email + " in " + response.results()));
	}
}
