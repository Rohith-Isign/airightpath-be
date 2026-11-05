package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.dto.voice.VoiceEvaluationResult.CategoryScore;
import com.rightpath.entity.EvaluationCategory;

class EvaluationScoreReconcilerTest {

    private static EvaluationCategory category(String name, double weight) {
        return EvaluationCategory.builder().categoryName(name).weight(weight).build();
    }

    private static CategoryScore score(String name, double score) {
        return CategoryScore.builder().category(name).score(score).weight(0.5).feedback("f").build();
    }

    private static VoiceEvaluationResult result(double overall, CategoryScore... scores) {
        return VoiceEvaluationResult.builder().overallScore(overall).categoryScores(List.of(scores)).build();
    }

    @Test
    void overallIsTheWeightedMeanOfTheAreaScores() {
        VoiceEvaluationResult result = result(9.0, score("A", 4), score("B", 8));

        double before = EvaluationScoreReconciler.reconcile(result, List.of(category("A", 75), category("B", 25)));

        assertEquals(9.0, before);
        assertEquals(5.0, result.getOverallScore());
        assertEquals(0.75, result.getCategoryScores().get(0).getWeight(), 1e-9);
    }

    /** Weights that add up to 200 count in proportion, so the score still lands between 0 and 10. */
    @Test
    void weightsTotallingMoreThanAHundredStillGiveAScoreOutOfTen() {
        VoiceEvaluationResult result = result(0, score("A", 10), score("B", 10));

        EvaluationScoreReconciler.reconcile(result, List.of(category("A", 150), category("B", 50)));

        assertEquals(10.0, result.getOverallScore());
    }

    @Test
    void anAreaNotInTheConfiguredListDoesNotCount() {
        VoiceEvaluationResult result = result(0, score("A", 6), score("Invented", 10));

        EvaluationScoreReconciler.reconcile(result, List.of(category("A", 100)));

        assertEquals(6.0, result.getOverallScore());
    }

    @Test
    void withNothingToMatchTheModelsScoreIsLeftAlone() {
        VoiceEvaluationResult result = result(7.5, score("Other", 2));

        EvaluationScoreReconciler.reconcile(result, List.of(category("A", 100)));

        assertEquals(7.5, result.getOverallScore());
    }

    private static CategoryScore asked(String name, double score, List<Integer> questions) {
        return CategoryScore.builder().category(name).score(score).weight(0.5).feedback("f")
                .questionNumbers(questions).build();
    }

    @Test
    void anAreaNoQuestionTestedIsNotAssessedAndLowersTheOverall() {
        // A scores 8 on a real question; B was never asked about and the grader gave it a middling 5 anyway.
        VoiceEvaluationResult result = result(6.5, asked("A", 8, List.of(1, 2)), asked("B", 5, List.of()));

        EvaluationScoreReconciler.reconcile(result, List.of(category("A", 60), category("B", 40)));

        // 8 on the 60 per cent that was asked, nothing for the 40 per cent that was not: 4.8 of 10 overall.
        assertEquals(4.8, result.getOverallScore(), 1e-9, "untested areas add nothing, so partial completion scores lower");
        assertEquals(8.0, result.getAssessedScore(), 1e-9, "the mean on what was actually tested is kept separately");
        assertEquals(false, result.getCategoryScores().get(1).isAssessed());
        assertEquals(0.0, result.getCategoryScores().get(1).getScore());
        assertEquals(0.6, result.getCoverage(), 1e-9);
    }

    @Test
    void withNoAreaAssessedThereIsNoOverallScore() {
        VoiceEvaluationResult result = result(6.0, asked("A", 5, List.of()), asked("B", 6, List.of()));

        EvaluationScoreReconciler.reconcile(result, List.of(category("A", 50), category("B", 50)));

        assertEquals(0.0, result.getOverallScore());
        assertEquals(0.0, result.getCoverage(), 1e-9);
    }

    @Test
    void anOlderResultWithNoQuestionNumbersIsStillScoredEverywhere() {
        VoiceEvaluationResult result = result(0, score("A", 4), score("B", 8));

        EvaluationScoreReconciler.reconcile(result, List.of(category("A", 50), category("B", 50)));

        assertEquals(6.0, result.getOverallScore());
        assertEquals(1.0, result.getCoverage(), 1e-9);
    }
}
