package com.rightpath.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.entity.EvaluationCategory;
import com.rightpath.enums.InterviewRound;
import com.rightpath.repository.EvaluationCategoryRepository;

/**
 * Which categories a round is scored against.
 *
 * <p>Categories were per job, so a behavioural round was graded on the
 * technical round's list — which is how an L3 came back with a "Programming"
 * score for questions it never asked. Resolution now falls back round list →
 * shared list → per-round defaults, and each step of that chain is worth
 * pinning: a job configured before this existed must keep the scoring it has.</p>
 */
class RoundScopedCategoriesTest {

    private static final String JOB = "RP-AIML-01";

    private EvaluationCategoryRepository repository;
    private EvaluationCategoryFormatter formatter;

    @BeforeEach
    void setUp() {
        repository = mock(EvaluationCategoryRepository.class);
        formatter = new EvaluationCategoryFormatter(repository);
    }

    private EvaluationCategory category(String name, double weight) {
        return EvaluationCategory.builder().categoryName(name).weight(weight).build();
    }

    private List<String> names(List<EvaluationCategory> categories) {
        return categories.stream().map(EvaluationCategory::getCategoryName).toList();
    }

    @Test
    @DisplayName("a round's own list wins over the shared one")
    void roundListWins() {
        when(repository.findAllByJobPrefixAndRound(JOB, InterviewRound.L3_BEHAVIORAL))
                .thenReturn(List.of(category("Ownership", 60), category("Teamwork", 40)));
        when(repository.findAllByJobPrefixAndRoundIsNull(JOB))
                .thenReturn(List.of(category("Technical Skills", 100)));

        assertThat(names(formatter.getCategories(JOB, InterviewRound.L3_BEHAVIORAL)))
                .containsExactly("Ownership", "Teamwork");
    }

    @Test
    @DisplayName("a job configured before rounds existed keeps its shared list")
    void sharedListIsUsedWhenTheRoundHasNone() {
        when(repository.findAllByJobPrefixAndRound(JOB, InterviewRound.L2_TECHNICAL))
                .thenReturn(List.of());
        when(repository.findAllByJobPrefixAndRoundIsNull(JOB))
                .thenReturn(List.of(category("Technical Skills", 70), category("Communication", 30)));

        assertThat(names(formatter.getCategories(JOB, InterviewRound.L2_TECHNICAL)))
                .containsExactly("Technical Skills", "Communication");
    }

    @Test
    @DisplayName("lists are never merged — weights that total 100 must stay totalling 100")
    void listsAreNotMerged() {
        when(repository.findAllByJobPrefixAndRound(JOB, InterviewRound.L3_BEHAVIORAL))
                .thenReturn(List.of(category("Ownership", 100)));
        when(repository.findAllByJobPrefixAndRoundIsNull(JOB))
                .thenReturn(List.of(category("Technical Skills", 100)));

        List<EvaluationCategory> resolved = formatter.getCategories(JOB, InterviewRound.L3_BEHAVIORAL);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.stream().mapToDouble(EvaluationCategory::getWeight).sum()).isEqualTo(100);
    }

    @Test
    @DisplayName("an unconfigured behavioural round is scored on behavioural categories")
    void behaviouralDefaultsAreBehavioural() {
        when(repository.findAllByJobPrefixAndRound(JOB, InterviewRound.L3_BEHAVIORAL)).thenReturn(List.of());
        when(repository.findAllByJobPrefixAndRoundIsNull(JOB)).thenReturn(List.of());

        List<String> resolved = names(formatter.getCategories(JOB, InterviewRound.L3_BEHAVIORAL));

        // The five the brief asks for, however they are worded.
        assertThat(resolved).anyMatch(n -> n.contains("Communication"));
        assertThat(resolved).anyMatch(n -> n.contains("Ownership"));
        assertThat(resolved).anyMatch(n -> n.contains("Teamwork"));
        assertThat(resolved).anyMatch(n -> n.contains("Adaptability"));
        assertThat(resolved).anyMatch(n -> n.contains("Role Fit"));
        // And not the one that made an L3 score look absurd.
        assertThat(resolved).doesNotContain("Programming");
    }

    @Test
    @DisplayName("an unconfigured technical round keeps the list it has always had")
    void technicalDefaultsAreUnchanged() {
        when(repository.findAllByJobPrefixAndRound(JOB, InterviewRound.L2_TECHNICAL)).thenReturn(List.of());
        when(repository.findAllByJobPrefixAndRoundIsNull(JOB)).thenReturn(List.of());

        assertThat(names(formatter.getCategories(JOB, InterviewRound.L2_TECHNICAL)))
                .contains("Technical Skills", "Programming", "Logical Reasoning");
    }

    @Test
    @DisplayName("a null round is treated as technical, as everywhere else")
    void nullRoundReadsAsTechnical() {
        when(repository.findAllByJobPrefixAndRound(JOB, InterviewRound.L2_TECHNICAL)).thenReturn(List.of());
        when(repository.findAllByJobPrefixAndRoundIsNull(JOB)).thenReturn(List.of());

        assertThat(names(formatter.getCategories(JOB, null))).contains("Programming");
    }

    @Test
    @DisplayName("default weights total 100 for both rounds")
    void defaultWeightsTotalOneHundred() {
        // The grader is handed these as percentages and multiplies the scores by
        // them. A set that totals 90 quietly marks every candidate down.
        when(repository.findAllByJobPrefixAndRound(JOB, InterviewRound.L2_TECHNICAL)).thenReturn(List.of());
        when(repository.findAllByJobPrefixAndRound(JOB, InterviewRound.L3_BEHAVIORAL)).thenReturn(List.of());
        when(repository.findAllByJobPrefixAndRoundIsNull(JOB)).thenReturn(List.of());

        for (InterviewRound round : InterviewRound.values()) {
            double total = formatter.getCategories(JOB, round).stream()
                    .mapToDouble(EvaluationCategory::getWeight)
                    .sum();
            assertThat(total).as("weights for %s", round).isEqualTo(100);
        }
    }
}
