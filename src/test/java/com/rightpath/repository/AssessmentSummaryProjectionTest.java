package com.rightpath.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.dto.AssessmentSummaryDTO;
import com.rightpath.entity.Assessment;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.Users;
import com.rightpath.enums.AssessmentType;

/**
 * Exercises {@link AssessmentRepository#findSummariesByJobPrefix(String)} against
 * the real MySQL schema.
 *
 * <p>A JPQL constructor expression is not checked by the compiler: a field
 * reordered in the DTO, or a type that no constructor accepts, fails at
 * EntityManagerFactory bootstrap or at execution rather than at build time. This
 * runs the query, so the projection is verified as executed rather than as
 * intended.</p>
 *
 * <p>Fixtures use a job prefix nothing else can share, so a shared dev database
 * cannot make the expectations drift, and every insert is rolled back.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AssessmentSummaryProjectionTest {

    /** Matches only the fixtures below, keeping assertions independent of existing data. */
    private static final String FIXTURE_PREFIX = "ZYLNEX-SUMMARY-FIXTURE";

    private static final String CANDIDATE = "zylnex.summary.fixture@example.test";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AssessmentRepository repository;

    @BeforeEach
    void seed() {
        // assessment.candidate_email and .job_prefix are real foreign keys, so the
        // parents have to exist before an assignment can reference them.
        entityManager.persist(candidate());
        entityManager.persist(jobPost());

        // Assigned oldest first, inserted newest first, so the ordering assertion
        // below is testing the query's ORDER BY rather than insertion order.
        entityManager.persist(assessment(AssessmentType.CODING, 40, LocalDateTime.now().minusDays(1)));
        entityManager.persist(assessment(AssessmentType.APTITUDE, 75, LocalDateTime.now().minusDays(3)));
        entityManager.flush();
        entityManager.clear();
    }

    private Users candidate() {
        Users user = new Users();
        user.setEmail(CANDIDATE);
        user.setFirstName("Zylnex");
        user.setLastName("Fixture");
        user.setPassword("not-a-real-hash");
        // Unique and non-null in the schema; kept distinctive so it cannot collide
        // with a real record in a shared dev database.
        user.setMobileNumber("9990000000001");
        return user;
    }

    private JobPost jobPost() {
        return JobPost.builder()
                .jobPrefix(FIXTURE_PREFIX)
                .jobTitle("Zylnex Summary Fixture Role")
                .companyName("Zylnex Summary Fixture Co")
                .location("Hyderabad")
                .keySkills("Java, Spring")
                .jobType("Full-Time")
                .applicationDeadline(LocalDate.now().plusDays(30))
                .createdAt(LocalDate.now())
                .build();
    }

    private Assessment assessment(AssessmentType type, Integer passPercentage, LocalDateTime assignedAt) {
        Assessment assessment = new Assessment();
        assessment.setAssessmentType(type);
        assessment.setCandidateEmail(CANDIDATE);
        assessment.setJobPrefix(FIXTURE_PREFIX);
        assessment.setPassPercentage(passPercentage);
        assessment.setAssignedAt(assignedAt);
        assessment.setStartTime(assignedAt.plusHours(1));
        assessment.setDeadline(assignedAt.plusDays(7));
        // The columns the projection exists to avoid loading. Populated so the
        // test would still pass if they were being selected — the point of the
        // projection is bandwidth, and correctness must not depend on them.
        assessment.setQuestionPaper("[{\"question\":\"fixture\"}]");
        return assessment;
    }

    @Test
    void projectsEveryAssignmentOnTheJob() {
        List<AssessmentSummaryDTO> summaries = repository.findSummariesByJobPrefix(FIXTURE_PREFIX);

        assertEquals(2, summaries.size(), "both fixtures should come back");
        summaries.forEach(summary -> {
            assertEquals(FIXTURE_PREFIX, summary.getJobPrefix());
            assertEquals(CANDIDATE, summary.getCandidateEmail());
            assertNotNull(summary.getId(), "id must survive the projection");
        });
    }

    @Test
    void ordersAssignmentsOldestFirstPerCandidate() {
        List<AssessmentSummaryDTO> summaries = repository.findSummariesByJobPrefix(FIXTURE_PREFIX);

        // Clients take the last of each type as the attempt to grade, so the
        // order this returns in is part of the contract, not an incidental.
        assertEquals(AssessmentType.APTITUDE, summaries.get(0).getAssessmentType());
        assertEquals(AssessmentType.CODING, summaries.get(1).getAssessmentType());
        assertTrue(
                !summaries.get(0).getAssignedAt().isAfter(summaries.get(1).getAssignedAt()),
                "assignments should be ordered oldest first");
    }

    @Test
    void carriesThePassMarkEachPaperWasAssignedWith() {
        List<AssessmentSummaryDTO> summaries = repository.findSummariesByJobPrefix(FIXTURE_PREFIX);

        // The whole reason the endpoint exists: grading a row needs its own bar,
        // and a shared default would silently re-grade every paper.
        assertEquals(75, summaries.get(0).getPassPercentage());
        assertEquals(40, summaries.get(1).getPassPercentage());
    }

    @Test
    void returnsEmptyForAJobWithNoAssignments() {
        assertTrue(repository.findSummariesByJobPrefix(FIXTURE_PREFIX + "-ABSENT").isEmpty());
    }
}
