package com.rightpath.util;

import java.util.List;
import java.util.Locale;

import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.entity.EvaluationCategory;

/**
 * Makes the overall score follow from the area scores, instead of being a separate number the model made up.
 *
 * <p>The grader used to be asked for an overall score and per-area scores in the same reply, and both were stored as
 * given. Nothing tied them together: an interview with every area at 4 could come back 5.5 overall, or the weights
 * the admin configured could be echoed back wrongly, and the page then showed numbers that could not be reconciled.
 * The overall is now the weighted mean of the area scores, using the weights configured for the round.</p>
 *
 * <p>Weights are used as proportions of whatever they add up to, not as percentages that must reach 100. A list that
 * totals 130 (or 70) therefore still gives a score between 0 and 10, with every area counting in proportion to its
 * weight.</p>
 */
public final class EvaluationScoreReconciler {

    private EvaluationScoreReconciler() {
    }

    /**
     * Sets each area's weight to its share of the total, and the overall score to the weighted mean.
     *
     * <p>Areas the grader returned that are not in the configured list keep a weight of zero and do not count;
     * with no matching area at all the model's own overall score is left alone, since there is nothing to
     * recompute it from.</p>
     *
     * @return the overall score before it was replaced, for logging
     */
    public static double reconcile(VoiceEvaluationResult result, List<EvaluationCategory> configured) {
        double modelOverall = result.getOverallScore();
        List<VoiceEvaluationResult.CategoryScore> scores = result.getCategoryScores();
        if (scores == null || scores.isEmpty() || configured == null || configured.isEmpty()) {
            return modelOverall;
        }

        double totalWeight = 0;
        for (VoiceEvaluationResult.CategoryScore score : scores) {
            double weight = weightOf(score.getCategory(), configured);
            score.setScore(clamp(score.getScore()));
            totalWeight += weight;
            // Parked here as the raw configured weight; turned into a share below.
            score.setWeight(weight);
        }
        if (totalWeight <= 0) {
            return modelOverall;
        }

        double weighted = 0;
        for (VoiceEvaluationResult.CategoryScore score : scores) {
            double share = score.getWeight() / totalWeight;
            score.setWeight(share);
            weighted += score.getScore() * share;
        }
        result.setOverallScore(Math.round(clamp(weighted) * 10.0) / 10.0);
        return modelOverall;
    }

    private static double weightOf(String category, List<EvaluationCategory> configured) {
        if (category == null) {
            return 0;
        }
        String wanted = category.trim().toLowerCase(Locale.ROOT);
        return configured.stream()
                .filter(c -> c.getCategoryName() != null && c.getCategoryName().trim().toLowerCase(Locale.ROOT).equals(wanted))
                .mapToDouble(c -> Math.max(0, c.getWeight()))
                .findFirst()
                .orElse(0);
    }

    private static double clamp(double score) {
        return Math.max(0, Math.min(10, score));
    }
}
