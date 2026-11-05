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

    /**
     * The share of the scoring areas, by weight, that the questions actually asked about, 0.0 to 1.0. The overall
     * score is built only from those; the rest are not assessed and do not count for or against the candidate.
     */
    private Double coverage;

    /**
     * The mean score over the assessed areas only, out of 10: how well the candidate did on what was actually tested.
     * The overall score is lower than this whenever some areas were never reached, because an area not demonstrated
     * adds nothing to it.
     */
    private Double assessedScore;

    /** How much the score rests on: SUFFICIENT, PARTIAL (some areas never asked) or INSUFFICIENT (too little to judge). */
    private String evidenceLevel;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryScore {
        private String category;
        private double score;
        private double weight;
        private String feedback;

        /**
         * False when no question bore on this area: it was never asked about, so it is not assessed, shows as such and
         * adds nothing to the overall score. Absent (true) on an evaluation graded before this was recorded.
         */
        @Builder.Default
        private boolean assessed = true;

        /**
         * The interviewer's questions this score rests on, by the number each carries in the transcript the grader
         * was shown ("Question 3"). The results page turns them into "asked, answered, skipped" for the area and
         * lists them. Null on an evaluation graded before this was asked for; empty when the area was never asked
         * about.
         */
        private List<Integer> questionNumbers;
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
