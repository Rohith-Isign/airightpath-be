package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.dto.JobListingResponse;
import com.rightpath.dto.JobPostDTO;
import com.rightpath.dto.JobPostSearchRequest;
import com.rightpath.dto.JobPostSearchRequest.JobPostSearchRequestBuilder;
import com.rightpath.dto.JobStatusCountsDTO;
import com.rightpath.entity.JobPost;
import com.rightpath.repository.JobPostSpecifications;
import com.rightpath.util.BusinessSchedule;

/**
 * Exercises the paginated listing against a real MySQL schema, so the SQL built
 * from {@link JobPostSpecifications} (case-insensitive LIKE, separator-stripping
 * job-type comparison, deadline bucketing) is verified as executed rather than as
 * intended.
 *
 * <p>Runs against the dev profile's datasource (pinned here, not inherited from the
 * base config) and rolls every insert back.
 * Assertions are scoped to the seeded fixtures — via a distinctive company name
 * that the {@code search} filter can select on — so pre-existing rows in a shared
 * dev database cannot make the expectations drift.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ JobPostServiceImpl.class, BusinessSchedule.class })
class JobPostListingIntegrationTest {

	/** Matches only the fixtures below, keeping assertions independent of existing data. */
	private static final String FIXTURE_MARKER = "Zylnex Listing Fixture Co";

	/** Business timezone from {@code application.properties}; keeps "today" aligned with the service. */
	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private JobPostServiceImpl service;

	@BeforeEach
	void seed() {
		// Two dated-active, one undated (also active), two expired. Job types are
		// deliberately spelled inconsistently, as the production data is.
		persist("FIXT-FE-DEV-2026-005", "Junior Software Developer", "Full-Time", TODAY.plusDays(10));
		persist("FIXT-QA-TEST-2026-006", "QA Engineer", "full time", TODAY);
		persist("FIXT-BE-DEV-2026-007", "Backend Engineer", "Full-time", TODAY.minusDays(1));
		persist("FIXT-PM-LEAD-2026-008", "Product Manager", "Contract", TODAY.minusDays(30));
		persist("FIXT-IN-OPEN-2026-009", "Open Ended Internship", "Part-Time", null);
		entityManager.flush();
	}

	@Test
	void omittingStatusReturnsOnlyActivePostings() {
		// Default ordering is applicationDeadline ascending; MySQL sorts the undated
		// posting first.
		assertEquals(
				List.of("FIXT-IN-OPEN-2026-009", "FIXT-QA-TEST-2026-006", "FIXT-FE-DEV-2026-005"),
				prefixesOf(fixtures()));
	}

	@Test
	void statusAllReturnsEverything() {
		JobListingResponse listing = list(fixtures().status("ALL"));

		assertEquals(5, listing.getTotalElements());
		assertEquals(5, listing.getContent().size());
	}

	@Test
	void statusExpiredReturnsOnlyPastDeadlinesOldestFirst() {
		assertEquals(
				List.of("FIXT-PM-LEAD-2026-008", "FIXT-BE-DEV-2026-007"),
				prefixesOf(fixtures().status("expired")));
	}

	@Test
	void deadlineFallingTodayCountsAsActive() {
		List<String> active = prefixesOf(fixtures().search(FIXTURE_MARKER + " never").status("ACTIVE"));
		assertTrue(active.isEmpty(), "control: an unmatchable term must return nothing");

		assertTrue(prefixesOf(fixtures().status("ACTIVE")).contains("FIXT-QA-TEST-2026-006"),
				"a posting whose deadline is today must still be active");
	}

	@Test
	void searchMatchesJobPrefixAndTitleCaseInsensitively() {
		// Not scoped to the fixtures: 'dev' has to reach both the prefix and the
		// title. Every row it returns must genuinely contain the term somewhere.
		List<JobPostDTO> matches = list(JobPostSearchRequest.builder().search("dev").status("ALL")
				.size(100)).getContent();

		List<String> prefixes = matches.stream().map(JobPostDTO::getJobPrefix).toList();
		assertTrue(prefixes.contains("FIXT-FE-DEV-2026-005"), "matched by prefix and by title: " + prefixes);
		assertTrue(prefixes.contains("FIXT-BE-DEV-2026-007"), "matched by prefix: " + prefixes);
		for (JobPostDTO match : matches) {
			assertTrue(containsIgnoringCase(match, "dev"), "unexpected match: " + match.getJobPrefix());
		}
	}

	@Test
	void searchEscapesWildcardsTypedByTheUser() {
		assertEquals(0, list(JobPostSearchRequest.builder().search("FIXT-%-DEV").status("ALL")).getTotalElements(),
				"'%' typed by the user must match literally, not as a wildcard");
	}

	@Test
	void jobTypeMatchesRegardlessOfCaseAndSeparators() {
		JobListingResponse listing = list(fixtures().jobType("full-time").status("ALL"));

		assertEquals(3, listing.getTotalElements());
		assertEquals(
				List.of("FIXT-BE-DEV-2026-007", "FIXT-QA-TEST-2026-006", "FIXT-FE-DEV-2026-005"),
				prefixesOf(listing));
		assertEquals(3, list(fixtures().jobType("FULL TIME").status("ALL")).getTotalElements());
		assertEquals(3, list(fixtures().jobType("Full_Time").status("ALL")).getTotalElements());
	}

	@Test
	void combinedFiltersPageWithoutOverlapping() {
		JobPostSearchRequestBuilder request = fixtures().jobType("full-time").status("ALL").size(2);

		JobListingResponse first = list(request.page(0));
		JobListingResponse second = list(request.page(1));

		assertEquals(3, first.getTotalElements());
		assertEquals(2, first.getTotalPages());
		assertEquals(2, first.getContent().size());
		assertEquals(1, second.getContent().size());

		List<String> pagedThrough = new ArrayList<>(prefixesOf(first));
		pagedThrough.addAll(prefixesOf(second));
		assertEquals(3, pagedThrough.stream().distinct().count(), "pages overlapped: " + pagedThrough);
	}

	@Test
	void pagesAndCountsWithNoFiltersAtAll() {
		// The commonest real request (?page=0&size=20) builds no search/jobType
		// predicate at all, so both the page query and the count queries run with an
		// empty specification.
		JobListingResponse listing = list(JobPostSearchRequest.builder().page(0).size(20));

		assertTrue(listing.getTotalElements() >= 3, "seeded active fixtures must be included");
		assertTrue(listing.getContent().size() <= 20, "page must respect the requested size");
		JobStatusCountsDTO counts = listing.getCounts();
		assertEquals(counts.getAll(), counts.getActive() + counts.getExpired());
		assertEquals(counts.getActive(), listing.getTotalElements(),
				"a default (ACTIVE) listing must total the active bucket");
	}

	@Test
	void pageSizeIsCappedAtHundred() {
		assertEquals(100, list(fixtures().status("ALL").size(500)).getSize());
	}

	@Test
	void countsCoverTheWholeFilterNotJustThePage() {
		JobStatusCountsDTO counts = list(fixtures().size(1)).getCounts();

		assertEquals(5, counts.getAll());
		assertEquals(3, counts.getActive());
		assertEquals(2, counts.getExpired());
	}

	@Test
	void countsHonourSearchAndJobTypeAndIgnoreStatus() {
		JobStatusCountsDTO counts = service.getStatusCounts(FIXTURE_MARKER, "FULL TIME");

		assertEquals(3, counts.getAll());
		assertEquals(2, counts.getActive());
		assertEquals(1, counts.getExpired());
		assertEquals(counts.getAll(), counts.getActive() + counts.getExpired());
	}

	@Test
	void distinctJobTypesCollapseSpellingsAndSortAlphabetically() {
		List<String> jobTypes = service.getDistinctJobTypes();

		// "Full-Time" / "full time" / "Full-time" are one option, not three.
		assertEquals(1, countMatching(jobTypes, "full-time"), "expected one full-time option in " + jobTypes);
		assertEquals(1, countMatching(jobTypes, "part time"), "expected one part-time option in " + jobTypes);
		assertEquals(1, countMatching(jobTypes, "Contract"), "expected one contract option in " + jobTypes);
		assertEquals(jobTypes.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), jobTypes,
				"job types must be sorted alphabetically ignoring case");
	}

	@Test
	void sortByCreatedAtDescendingIsSupported() {
		JobListingResponse listing = list(fixtures().status("ALL").sort("createdAt,desc"));

		assertEquals(5, listing.getTotalElements());
		// All fixtures share a creation date, so the id tiebreaker decides: newest first.
		assertEquals("FIXT-IN-OPEN-2026-009", listing.getContent().get(0).getJobPrefix());
	}

	private void persist(String prefix, String title, String jobType, LocalDate deadline) {
		entityManager.persist(JobPost.builder()
				.jobPrefix(prefix)
				.jobTitle(title)
				.companyName(FIXTURE_MARKER)
				.location("Hyderabad")
				.keySkills("Java, Spring")
				.jobType(jobType)
				.applicationDeadline(deadline)
				.createdAt(TODAY)
				.build());
	}

	/** A request scoped to the seeded fixtures by company name. */
	private static JobPostSearchRequestBuilder fixtures() {
		return JobPostSearchRequest.builder().search(FIXTURE_MARKER);
	}

	private JobListingResponse list(JobPostSearchRequestBuilder request) {
		return service.searchJobPosts(request.build());
	}

	private List<String> prefixesOf(JobPostSearchRequestBuilder request) {
		return prefixesOf(list(request));
	}

	private static List<String> prefixesOf(JobListingResponse listing) {
		return listing.getContent().stream().map(JobPostDTO::getJobPrefix).toList();
	}

	private static long countMatching(List<String> jobTypes, String wanted) {
		String key = JobPostSpecifications.normalizeJobType(wanted);
		return jobTypes.stream().filter(type -> key.equals(JobPostSpecifications.normalizeJobType(type))).count();
	}

	private static boolean containsIgnoringCase(JobPostDTO post, String term) {
		return List.of(post.getJobTitle(), post.getCompanyName(), post.getKeySkills(),
				post.getLocation(), post.getJobPrefix()).stream()
				.filter(java.util.Objects::nonNull)
				.anyMatch(value -> value.toLowerCase().contains(term));
	}
}
