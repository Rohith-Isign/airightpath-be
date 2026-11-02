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
}
