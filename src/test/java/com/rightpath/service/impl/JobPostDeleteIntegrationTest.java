package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.dto.JobPostDTO;
import com.rightpath.dto.JobPostDeletionDTO;
import com.rightpath.dto.JobPostSearchRequest;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.Users;
import com.rightpath.enums.ApplicationStatus;
import com.rightpath.exceptions.JobPostNotFoundException;
import com.rightpath.util.BusinessSchedule;

/**
 * Covers deleting a posting: what disappears, what survives, and what a second
 * attempt does.
 *
 * <p>Runs against the dev profile's datasource (pinned here, not inherited from the
 * base config) and rolls every insert back. Assertions address fixtures by id or by a
 * distinctive marker, so pre-existing rows in a shared dev database are irrelevant.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ JobPostServiceImpl.class, BusinessSchedule.class })
class JobPostDeleteIntegrationTest {

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
	private static final String MARKER = "Zylnex Delete Fixture Co";
	private static final String PREFIX = "FIXT-DEL-2026-001";

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private JobPostServiceImpl service;

	private Long jobId;

	@BeforeEach
	void seed() {
		jobId = persistJob(PREFIX, "Mistakenly Created Role").getId();
	}

	@Test
	void deletingAnEmptyPostingRemovesItFromEveryListing() {
		JobPostDeletionDTO deleted = service.deleteJobPost(jobId);

		assertEquals(jobId, deleted.getId());
		assertEquals(PREFIX, deleted.getJobPrefix());
		assertEquals(0, deleted.getApplicationsRetained());
		assertNotNull(deleted.getDeletedAt());

		entityManager.flush();
		entityManager.clear();
		assertTrue(livePrefixes().stream().noneMatch(PREFIX::equals), "must be gone from getPost");
		assertEquals(0, listMarkedFixtures().getTotalElements(), "must be gone from the paginated listing");
	}

	@Test
	void archivedPostingIsHiddenEvenFromStatusAll() {
		service.deleteJobPost(jobId);
		entityManager.flush();
		entityManager.clear();

		// status=ALL is about deadlines, not archival: it must never resurface a deleted job.
		assertEquals(0, listMarkedFixtures().getTotalElements());
		assertEquals(0, service.getStatusCounts(MARKER, null).getAll());
	}

	@Test
	void archivedPostingLeavesTheJobTypeDropdown() {
		Long soleHolderId = persistJob("FIXT-DEL-2026-050", "Only Holder Of This Type", "Zylnex-Only-Type").getId();
		entityManager.flush();
		assertTrue(service.getDistinctJobTypes().contains("Zylnex-Only-Type"));

		service.deleteJobPost(soleHolderId);
		entityManager.flush();
		entityManager.clear();

		assertFalse(service.getDistinctJobTypes().contains("Zylnex-Only-Type"),
				"a type only an archived job used must not stay in the dropdown");
	}

	@Test
	void deletingAPostingWithApplicationsKeepsTheApplications() {
		Long applicationId = persistApplication("delete.fixture@example.test").getId();

		JobPostDeletionDTO deleted = service.deleteJobPost(jobId);

		assertEquals(1, deleted.getApplicationsRetained(), "the admin must be told what was kept");

		entityManager.flush();
		entityManager.clear();
		JobApplicationForCandidate application = entityManager.find(JobApplicationForCandidate.class, applicationId);
		assertNotNull(application, "candidate history must survive the delete");
		assertEquals(ApplicationStatus.SHORTLISTED, application.getStatus());
		// The row it points at still exists, so there is no orphan and no partial delete.
		assertNotNull(application.getJobPost(), "the application must not be orphaned");
		assertEquals(PREFIX, application.getJobPost().getJobPrefix());
		assertNotNull(application.getJobPost().getDeletedAt(), "the job it points at is archived, not missing");
	}

	@Test
	void unknownIdIsNotFound() {
		assertThrows(JobPostNotFoundException.class, () -> service.deleteJobPost(-1L));
	}

	@Test
	void deletingTwiceReportsNotFoundTheSecondTime() {
		service.deleteJobPost(jobId);
		entityManager.flush();

		assertThrows(JobPostNotFoundException.class, () -> service.deleteJobPost(jobId));
	}

	@Test
	void archivedPostingRecordsWhoDeletedIt() {
		service.deleteJobPost(jobId);

		entityManager.flush();
		entityManager.clear();
		JobPost archived = entityManager.find(JobPost.class, jobId);
		assertNotNull(archived, "the row is kept, not removed");
		assertNotNull(archived.getDeletedAt());
		assertNotNull(archived.getDeletedBy());
		assertEquals(TODAY, archived.getDeletedAt().toLocalDate());
	}

	@Test
	void archivedPrefixStaysReservedForever() {
		service.deleteJobPost(jobId);
		entityManager.flush();

		// The archived row still holds the prefix, and job_prefix is unique — so no future
		// posting can take it and make the old applications ambiguous.
		assertThrows(Exception.class, () -> {
			entityManager.persist(JobPost.builder()
					.jobPrefix(PREFIX)
					.jobTitle("Trying to reuse the prefix")
					.companyName(MARKER)
					.createdAt(TODAY)
					.build());
			entityManager.flush();
		});
	}

	@Test
	void candidatesCannotApplyToAnArchivedPostingById() {
		service.deleteJobPost(jobId);
		entityManager.flush();

		// The /api/jobs/apply/{jobId} path: a clean not-found, never a 500 or a silent apply.
		assertThrows(JobPostNotFoundException.class,
				() -> service.applyToJob(jobId, "delete.fixture@example.test"));
	}

	private com.rightpath.dto.JobListingResponse listMarkedFixtures() {
		return service.searchJobPosts(JobPostSearchRequest.builder().search(MARKER).status("ALL").build());
	}

	private List<String> livePrefixes() {
		return service.getAllJobPosts().stream().map(JobPostDTO::getJobPrefix).toList();
	}

	private JobPost persistJob(String prefix, String title) {
		return persistJob(prefix, title, "Full-Time");
	}

	private JobPost persistJob(String prefix, String title, String jobType) {
		return entityManager.persistFlushFind(JobPost.builder()
				.jobPrefix(prefix)
				.jobTitle(title)
				.companyName(MARKER)
				.location("Hyderabad")
				.keySkills("Java, Spring")
				.jobType(jobType)
				.numberOfOpenings(1)
				.applicationDeadline(TODAY.plusDays(10))
				.createdAt(TODAY)
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
}
