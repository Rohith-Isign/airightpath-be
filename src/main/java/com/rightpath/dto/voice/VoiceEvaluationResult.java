package com.rightpath.dto.voice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationResult {

    private double overallScore;
    private String recommendation;
    private List<CategoryScore> categoryScores;
    private SpeechAnalysis speechAnalysis;
    private String summary;
    private List<String> strengths;
    private List<String> areasForImprovement;

    /**
     * How sure the grader is of its own verdict, 0.0 to 1.0.
     *
     * <p>Asked for explicitly, because a model given a thin transcript does not
     * hedge — it produces the same confident paragraph it would for a full one.
     * Null on an evaluation graded before this was asked for, and on one the
     * model declined to answer, which is itself treated as low.</p>
     *
     * <p>Not the same thing as {@link SpeechAnalysis#confidenceScore}, which is
     * how confident the <em>candidate</em> sounded. The two being named alike is
     * a trap: one is evidence about the person, the other is a caveat on the
     * machine's reading of them.</p>
     */
    private Double confidence;

    /** Whether a person should look at this before it is acted on. */
    private boolean needsHumanReview;

    /** Why, in words a reviewer can act on. Empty when no review is needed. */
    private List<String> reviewReasons;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryScore {
        private String category;
        private double score;
        private double weight;
        private String feedback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpeechAnalysis {
        private double averageWordsPerMinute;
        private int totalFillerWords;
        private double confidenceScore;
        private String paceAssessment;
        private String articulationFeedback;
    }
}
