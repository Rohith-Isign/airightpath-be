package com.rightpath.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.entity.QuestionBankAssignment;
import com.rightpath.entity.QuestionBankItem;
import com.rightpath.enums.AssessmentType;
import com.rightpath.repository.QuestionBankAssignmentRepository.UsageRow;

/**
 * "Given N times to M different candidates" and "never given", as the database counts them.
 *
 * <p>Real MySQL schema, a job prefix nothing else can share, every insert rolled back.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class QuestionBankUsageTest {

    private static final String PREFIX = "ZYLNEX-USAGE-FIXTURE";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private QuestionBankAssignmentRepository assignments;

    private QuestionBankItem item(String hash) {
        return entityManager.persistFlushFind(QuestionBankItem.builder()
                .jobPrefix(PREFIX)
                .assessmentType(AssessmentType.CODING)
                .contentHash(hash)
                .content("{\"title\":\"fixture " + hash + "\"}")
                .source("AI_GENERATED")
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void give(QuestionBankItem item, long assessmentId, String email, int daysAgo) {
        entityManager.persist(QuestionBankAssignment.builder()
                .itemId(item.getId())
                .assessmentId(assessmentId)
                .candidateEmail(email)
                .jobPrefix(PREFIX)
                .assignedAt(LocalDateTime.now().minusDays(daysAgo))
                .build());
    }

    @Test
    void countsHowOftenAQuestionWasGivenAndToHowManyDifferentPeople() {
        QuestionBankItem used = item("a".repeat(64));
        // One candidate twice (a repeat exam), another once.
        give(used, 9_000_001L, "one@example.test", 5);
        give(used, 9_000_002L, "one@example.test", 1);
        give(used, 9_000_003L, "two@example.test", 3);
        entityManager.flush();
        entityManager.clear();

        UsageRow row = assignments.usageByItem().stream()
                .filter(usage -> usage.getItemId().equals(used.getId())).findFirst().orElseThrow();

        assertEquals(3, row.getTimesAssigned());
        assertEquals(2, row.getUniqueCandidates(), "the repeat is one person, not two");
        assertTrue(row.getLastAssignedAt().isAfter(LocalDateTime.now().minusDays(2)));
    }

    @Test
    void aQuestionNeverGivenToAnyoneHasNoUsageRow() {
        QuestionBankItem unused = item("b".repeat(64));
        entityManager.flush();
        entityManager.clear();

        List<UsageRow> rows = assignments.usageByItem();
        assertNull(rows.stream().filter(usage -> usage.getItemId().equals(unused.getId())).findFirst().orElse(null),
                "no assignments means no row, which is how the bank knows it is unused");
    }

    @Test
    void theSameQuestionOnTheSameAssessmentCannotBeRecordedTwice() {
        QuestionBankItem item = item("c".repeat(64));
        give(item, 9_000_010L, "one@example.test", 1);
        entityManager.flush();

        assertTrue(assignments.existsByItemIdAndAssessmentId(item.getId(), 9_000_010L));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            give(item, 9_000_010L, "one@example.test", 1);
            entityManager.flush();
        });
    }
}
