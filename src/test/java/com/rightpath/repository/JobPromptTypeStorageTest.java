package com.rightpath.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.enums.PromptType;

/**
 * Every prompt type the application knows must be storable.
 *
 * <p>Hibernate maps {@code @Enumerated(EnumType.STRING)} on MySQL to a native
 * {@code ENUM} column listing the constants present when the table was created,
 * and {@code ddl-auto: update} never alters an existing column. Adding
 * {@code INTERVIEW_L2_TECHNICAL} and {@code INTERVIEW_L3_BEHAVIORAL} to
 * {@link PromptType} therefore left the column as
 * {@code enum('APTITUDE','CODING','INTERVIEW')} — and saving a round prompt
 * answered 500. The round-prompt feature had never once worked.</p>
 *
 * <p>Read against the live schema rather than by inserting a row: a prompt row
 * needs a job post behind it, and this is a question about the column, not about
 * the entity graph. The column is asserted to be text, so that the next
 * constant added to the enum cannot reintroduce the same failure — see
 * {@code docs/migration-job-prompt-type-varchar.md}.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobPromptTypeStorageTest {

    @Autowired
    private TestEntityManager entityManager;

    private String columnType(String column) {
        return (String) entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_prompt' "
                                + "AND COLUMN_NAME = :column")
                .setParameter("column", column)
                .getSingleResult();
    }

    @Test
    void thePromptTypeColumnHoldsAnyConstantTheEnumGrows() {
        String type = columnType("prompt_type").toLowerCase();

        assertTrue(type.startsWith("varchar"),
                "prompt_type is " + type + " — a database ENUM cannot store a constant added later, "
                        + "and ddl-auto never widens one. Run the migration in "
                        + "docs/migration-job-prompt-type-varchar.md.");

        int length = Integer.parseInt(type.replaceAll("\\D+", ""));
        int longest = List.of(PromptType.values()).stream()
                .mapToInt(value -> value.name().length())
                .max()
                .orElse(0);
        assertTrue(length >= longest,
                "prompt_type holds " + length + " characters but " + longest + " are needed");
    }

    @Test
    void thePromptStageColumnDoesTheSame() {
        // Same failure waiting on a new stage, so it is held to the same rule.
        assertTrue(columnType("prompt_stage").toLowerCase().startsWith("varchar"),
                "prompt_stage is still a database ENUM — see the same migration");
    }

    @Test
    void theRoundPromptTypesAreTheOnesThatWereUnstorable() {
        // Named so a future reader knows which values the migration was for,
        // and so removing them from the enum makes this fail loudly.
        List<String> names = List.of(PromptType.values()).stream().map(Enum::name).toList();

        assertTrue(names.contains("INTERVIEW_L2_TECHNICAL"));
        assertTrue(names.contains("INTERVIEW_L3_BEHAVIORAL"));
        assertEquals(3, names.stream().filter(n -> n.startsWith("INTERVIEW")).count(),
                "INTERVIEW plus the two rounds — a fourth means the migration's length needs checking");
    }
}
