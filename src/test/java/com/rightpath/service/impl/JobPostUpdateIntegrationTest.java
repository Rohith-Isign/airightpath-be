package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.dto.JobPostDTO;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.Users;
import com.rightpath.enums.ApplicationStatus;
import com.rightpath.exceptions.JobDeadlineInPastException;
import com.rightpath.exceptions.JobPostNotFoundException;
import com.rightpath.exceptions.JobPrefixImmutableException;
import com.rightpath.util.BusinessSchedule;

/**
 * Covers editing an existing posting against a real MySQL schema: what an admin may
 * change, what they may not, and what must survive the edit.
 *
 * <p>Runs against the dev profile's datasource — pinned here rather than inherited,
 * so the suite does not depend on which profile the base config happens to activate.
 * Inserts roll back, and every assertion addresses the fixtures by id, so
 * pre-existing rows in a shared dev database are irrelevant.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ JobPostServiceImpl.class, BusinessSchedule.class })
class JobPostUpdateIntegrationTest {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
	private static final String PREFIX = "FIXT-EDIT-2026-001";
	private static final LocalDate CREATED_ON = TODAY.minusMonths(2);

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private JobPostServiceImpl service;

	private Long jobId;

	@BeforeEach
	void seed() {
		JobPost job = persistJob(PREFIX, "Junior Sofware Develper", TODAY.plusDays(10));
		jobId = job.getId();
	}

	@Test
	void correctsFieldsAndExposesThemThroughTheListing() {
		JobPostDTO edit = editOf(PREFIX);
		edit.setJobTitle("Junior Software Developer");
		edit.setCompanyName("Rightpath Technologies");

		JobPost saved = service.updateJobPost(jobId, edit);

		assertEquals("Junior Software Developer", saved.getJobTitle());
		assertEquals("Rightpath Technologies", saved.getCompanyName());

		entityManager.flush();
		entityManager.clear();
		JobPostDTO listed = service.getAllJobPosts().stream()
				.filter(dto -> PREFIX.equals(dto.getJobPrefix()))
				.findFirst()
				.orElseThrow();
		assertEquals("Junior Software Developer", listed.getJobTitle());
		assertEquals(jobId, listed.getId(), "the listing must expose the id clients edit by");
	}

	@Test
	void rejectsAPrefixChangeAndLeavesTheStoredPrefixAlone() {
		JobPostDTO edit = editOf("FIXT-EDIT-2026-999");
		edit.setJobTitle("Renamed");

		assertThrows(JobPrefixImmutableException.class, () -> service.updateJobPost(jobId, edit));

		entityManager.flush();
		entityManager.clear();
		JobPost stored = entityManager.find(JobPost.class, jobId);
		assertEquals(PREFIX, stored.getJobPrefix(), "prefix must be untouched");
		assertEquals("Junior Sofware Develper", stored.getJobTitle(), "a rejected edit must not persist anything");
	}

	@Test
	void treatsAnOmittedPrefixAsUnchanged() {
		JobPostDTO edit = editOf(null);
		edit.setJobTitle("Still Fine");

		JobPost saved = service.updateJobPost(jobId, edit);

		assertEquals(PREFIX, saved.getJobPrefix());
		assertEquals("Still Fine", saved.getJobTitle());
	}

	@Test
	void expiredJobKeepsItsOwnPastDeadlineWhileBeingCorrected() {
		LocalDate expired = TODAY.minusDays(7);
		Long closedJobId = persistJob("FIXT-EDIT-2026-002", "Closed Rle", expired).getId();

		JobPostDTO edit = editOf(closedJobId, "FIXT-EDIT-2026-002");
		edit.setJobTitle("Closed Role");
		edit.setApplicationDeadline(expired);

		JobPost saved = service.updateJobPost(closedJobId, edit);

		assertEquals("Closed Role", saved.getJobTitle());
		assertEquals(expired, saved.getApplicationDeadline(), "the posting must stay closed, not silently reopen");
	}

	@Test
	void rejectsADifferentPastDeadline() {
		JobPostDTO edit = editOf(PREFIX);
		edit.setApplicationDeadline(TODAY.minusDays(1));

		assertThrows(JobDeadlineInPastException.class, () -> service.updateJobPost(jobId, edit));
	}

	@Test
	void acceptsTodayAndFutureDeadlines() {
		JobPostDTO toToday = editOf(PREFIX);
		toToday.setApplicationDeadline(TODAY);
		assertEquals(TODAY, service.updateJobPost(jobId, toToday).getApplicationDeadline());

		JobPostDTO toFuture = editOf(PREFIX);
		toFuture.setApplicationDeadline(TODAY.plusMonths(1));
		assertEquals(TODAY.plusMonths(1), service.updateJobPost(jobId, toFuture).getApplicationDeadline());
	}

	@Test
	void allowsClearingTheDeadline() {
		JobPostDTO edit = editOf(PREFIX);
		edit.setApplicationDeadline(null);

		assertNull(service.updateJobPost(jobId, edit).getApplicationDeadline());
	}

	@Test
	void unknownIdIsNotFound() {
		JobPostDTO edit = editOf(PREFIX);

		assertThrows(JobPostNotFoundException.class, () -> service.updateJobPost(-1L, edit));
	}

	@Test
	void keepsCreatedAtAndStampsTheEdit() {
		JobPostDTO edit = editOf(PREFIX);
		edit.setJobTitle("Stamped");

		JobPost saved = service.updateJobPost(jobId, edit);

		assertEquals(CREATED_ON, saved.getCreatedAt(), "createdAt is not the admin's to rewrite");
		assertNotNull(saved.getUpdatedAt(), "updatedAt must be stamped");
		assertEquals(TODAY, saved.getUpdatedAt().toLocalDate());
		assertNotNull(saved.getUpdatedBy(), "the editing admin must be recorded");
	}

	@Test
	void existingApplicationsSurviveTheEdit() {
		JobApplicationForCandidate application = persistApplication("fixture.candidate@example.test");
		Long applicationId = application.getId();

		JobPostDTO edit = editOf(PREFIX);
		edit.setJobTitle("Junior Software Developer");
		edit.setNumberOfOpenings(5);
		service.updateJobPost(jobId, edit);

		entityManager.flush();
		entityManager.clear();
		JobApplicationForCandidate reloaded = entityManager.find(JobApplicationForCandidate.class, applicationId);
		assertNotNull(reloaded, "the edit must not remove applications");
		assertEquals(ApplicationStatus.SHORTLISTED, reloaded.getStatus(), "application state must be untouched");
		assertEquals(jobId, reloaded.getJobPost().getId(), "the application must still point at the same posting");
		assertEquals(PREFIX, reloaded.getJobPost().getJobPrefix());
	}

	@Test
	void replacesRatherThanMergesEditableFields() {
		// A field the client sends as null is cleared, not kept: the endpoint is a full
		// replace, so the UI must post the whole object.
		JobPostDTO edit = editOf(PREFIX);
		edit.setKeySkills(null);

		JobPost saved = service.updateJobPost(jobId, edit);

		assertNull(saved.getKeySkills());
		assertEquals("Junior Sofware Develper", saved.getJobTitle(),
				"fields the client echoed back unchanged keep their value");
	}

	private JobPost persistJob(String prefix, String title, LocalDate deadline) {
		return entityManager.persistFlushFind(JobPost.builder()
				.jobPrefix(prefix)
				.jobTitle(title)
				.companyName("Rightpath")
				.location("Hyderabad")
				.keySkills("Java, Spring")
				.jobType("Full-Time")
				.numberOfOpenings(2)
				.contactEmail("hr@example.test")
				.applicationDeadline(deadline)
				.createdAt(CREATED_ON)
				.build());
	}

	private JobApplicationForCandidate persistApplication(String email) {
		Users candidate = new Users();
		candidate.setEmail(email);
		candidate.setFirstName("Asha");
		candidate.setLastName("Rao");
		// Users enforces a special character on persist; the value is never used here.
		candidate.setPassword("Fixture@1");
		candidate.setMobileNumber("9" + System.nanoTime() % 1000000000L);
		entityManager.persist(candidate);

		return entityManager.persistFlushFind(JobApplicationForCandidate.builder()
				.firstName("Asha")
				.lastName("Rao")
				.user(candidate)
				.jobPost(entityManager.find(JobPost.class, jobId))
				.status(ApplicationStatus.SHORTLISTED)
				.build());
	}

	/** The payload the edit screen posts: the loaded job, echoed back in full. */
	private JobPostDTO editOf(String jobPrefix) {
		return editOf(jobId, jobPrefix);
	}

	private JobPostDTO editOf(Long id, String jobPrefix) {
		JobPost stored = entityManager.find(JobPost.class, id);
		return JobPostDTO.builder()
				.id(stored.getId())
				.jobPrefix(jobPrefix)
				.jobTitle(stored.getJobTitle())
				.companyName(stored.getCompanyName())
				.location(stored.getLocation())
				.keySkills(stored.getKeySkills())
				.jobType(stored.getJobType())
				.numberOfOpenings(stored.getNumberOfOpenings())
				.contactEmail(stored.getContactEmail())
				.applicationDeadline(stored.getApplicationDeadline())
				.build();
	}
}
