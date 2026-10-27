package com.rightpath.dto.voice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PerformanceSnapshot {

    private int totalQuestionsAsked;
    private int totalSkips;
    private int consecutiveSkips;
    private double averageWordCount;
    private double averageConfidenceScore;
    private int consecutiveShortAnswers;
    private double skipRatio;
    private boolean earlyTerminationSuggested;

    /**
     * Mean of the interviewer's own 0-10 ratings of the last few answers.
     *
     * <p>Null until enough answers have been rated to average. Every other
     * number here measures how the candidate <em>spoke</em> — length, pace,
     * vocal confidence — and none of them can tell a fluent wrong answer from a
     * correct one. This is the only read on whether the answers were any good,
     * which is why difficulty is steered from it and not from those.</p>
     */
    private Double recentAverageScore;

    /** How many recent answers carried a rating, for callers deciding whether to act. */
    private int scoredAnswerCount;

    /** -1 to make the next question easier, 0 to hold, +1 to make it harder. */
    private int difficultyDirection;
}
