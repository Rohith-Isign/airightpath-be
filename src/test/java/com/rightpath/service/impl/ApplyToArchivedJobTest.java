package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rightpath.dto.JobApplicationForCandidateDTO;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.Users;
import com.rightpath.exceptions.JobPostNotFoundException;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.JobPostRepository;
import com.rightpath.repository.UsersRepository;

/**
 * The public apply link must answer cleanly for a prefix whose posting has been
 * deleted — a candidate following a stale link gets "no longer available" (404), not a
 * server error, and certainly not a successful application against an archived job.
 */
class ApplyToArchivedJobTest {

	private static final String PREFIX = "FIXT-DEL-2026-001";

	private JobApplicationForCandidateRepository applications;
	private UsersRepository users;
	private JobPostRepository jobPosts;
	private JobApplicationForCandidateServiceImpl service;

	@BeforeEach
	void setUp() {
		applications = mock(JobApplicationForCandidateRepository.class);
		users = mock(UsersRepository.class);
		jobPosts = mock(JobPostRepository.class);
		service = new JobApplicationForCandidateServiceImpl(applications, users, jobPosts, "https://example.test");

		Users candidate = new Users();
		candidate.setEmail("candidate@example.test");
		candidate.setFirstName("Asha");
		candidate.setLastName("Rao");
		when(users.findById("candidate@example.test")).thenReturn(Optional.of(candidate));
	}

	@Test
	void applyingToAnArchivedPostingIsNotFound() {
		when(jobPosts.findByJobPrefix(PREFIX)).thenReturn(Optional.of(archivedJob()));

		JobPostNotFoundException error = assertThrows(JobPostNotFoundException.class,
				() -> service.applyForJob(applyRequest()));

		assertTrue(error.getMessage().contains("no longer available"),
				"candidate-facing wording, got: " + error.getMessage());
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
	}

	@Test
	void applyingToAPrefixThatNeverExistedIsAlsoNotFound() {
		when(jobPosts.findByJobPrefix(PREFIX)).thenReturn(Optional.empty());

		assertThrows(JobPostNotFoundException.class, () -> service.applyForJob(applyRequest()));
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
	}

	@Test
	void doesNotEvenLookUpTheCandidateBeforeRejectingAnArchivedPosting() {
		when(jobPosts.findByJobPrefix(PREFIX)).thenReturn(Optional.of(archivedJob()));

		assertThrows(JobPostNotFoundException.class, () -> service.applyForJob(applyRequest()));

		// The resume is never read and no email is queued for a job that is gone.
		verify(applications, never()).save(any(JobApplicationForCandidate.class));
		verify(jobPosts, never()).save(any(JobPost.class));
		verify(applications, never()).findByJobPrefixAndEmail(anyString(), anyString());
	}

	private static JobPost archivedJob() {
		return JobPost.builder()
				.id(7L)
				.jobPrefix(PREFIX)
				.jobTitle("Deleted Role")
				.keySkills("Java")
				.applicationDeadline(LocalDate.now().plusDays(30))
				.deletedAt(LocalDateTime.now())
				.deletedBy("admin@example.test")
				.build();
	}

	private static JobApplicationForCandidateDTO applyRequest() {
		JobApplicationForCandidateDTO dto = new JobApplicationForCandidateDTO();
		dto.setEmail("candidate@example.test");
		dto.setJobPrefix(PREFIX);
		dto.setFirstName("Asha");
		dto.setLastName("Rao");
		dto.setMobileNumber("9000000000");
		return dto;
	}
}
