package com.rightpath.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rightpath.dto.voice.PerformanceSnapshot;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.repository.VoiceConversationEntryRepository;

@Service
public class CandidatePerformanceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CandidatePerformanceAnalyzer.class);

    private static final String SKIPPED_CONTENT = "[NO RESPONSE - SKIPPED]";

    private final VoiceConversationEntryRepository entryRepository;

    @Value("${interview.early-termination.min-questions:4}")
    private int minQuestions;

    @Value("${interview.early-termination.skip-ratio-threshold:0.5}")
    private double skipRatioThreshold;

    @Value("${interview.early-termination.min-avg-word-count:15}")
    private int minAvgWordCount;

    @Value("${interview.early-termination.min-confidence-score:30.0}")
    private double minConfidenceScore;

    @Value("${interview.early-termination.consecutive-skip-threshold:2}")
    private int consecutiveSkipThreshold;

    @Value("${interview.early-termination.consecutive-short-answer-threshold:3}")
    private int consecutiveShortAnswerThreshold;

    /**
     * How many recent answers difficulty is judged on.
     *
     * <p>Three. One answer is noise — a candidate who fumbles a question they
     * happened not to revise has not become a weaker engineer, and swinging the
     * interview on it produces a seesaw. Much more than three and the interview
     * is still being pitched at how they opened rather than how they are doing
     * now.</p>
     */
    @Value("${interview.difficulty.window:3}")
    private int difficultyWindow;

    /** Fewest rated answers before difficulty moves at all. */
    @Value("${interview.difficulty.min-scored-answers:2}")
    private int difficultyMinScoredAnswers;

    /** At or above this mean, stretch the candidate. */
    @Value("${interview.difficulty.raise-above:7.5}")
    private double difficultyRaiseAbove;

    /** At or below this mean, ease off. */
    @Value("${interview.difficulty.lower-below:4.5}")
    private double difficultyLowerBelow;

    public CandidatePerformanceAnalyzer(VoiceConversationEntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    public PerformanceSnapshot analyze(CandidateInterviewSchedule schedule) {
        List<VoiceConversationEntry> allEntries = entryRepository
                .findByInterviewScheduleIdOrderByTimestampAsc(schedule.getId());

        List<VoiceConversationEntry> candidateEntries = allEntries.stream()
                .filter(e -> e.getRole() == ConversationRole.CANDIDATE)
                .toList();

        int totalAnswers = candidateEntries.size();
        if (totalAnswers == 0) {
            return PerformanceSnapshot.builder()
                    .totalQuestionsAsked(schedule.getTotalQuestionsAsked())
                    .build();
        }

        // Count skips
        int totalSkips = 0;
        int consecutiveSkips = 0;
        int maxConsecutiveSkips = 0;

        // Track consecutive poor responses (short or skipped)
        int consecutivePoor = 0;
        int maxConsecutivePoor = 0;

        // Accumulators for averages
        double totalWordCount = 0;
        double totalConfidence = 0;
        int confidenceCount = 0;

        for (VoiceConversationEntry entry : candidateEntries) {
            boolean isSkip = SKIPPED_CONTENT.equals(entry.getContent());
            boolean isShort = !isSkip && entry.getWordCount() != null && entry.getWordCount() < minAvgWordCount;

            if (isSkip) {
                totalSkips++;
                consecutiveSkips++;
                consecutivePoor++;
            } else {
                consecutiveSkips = 0;

                if (isShort) {
                    consecutivePoor++;
                } else {
                    consecutivePoor = 0;
                }
            }

            maxConsecutiveSkips = Math.max(maxConsecutiveSkips, consecutiveSkips);
            maxConsecutivePoor = Math.max(maxConsecutivePoor, consecutivePoor);

            if (!isSkip && entry.getWordCount() != null) {
                totalWordCount += entry.getWordCount();
            }
            if (!isSkip && entry.getConfidenceScore() != null) {
                totalConfidence += entry.getConfidenceScore();
                confidenceCount++;
            }
        }

        int answeredCount = totalAnswers - totalSkips;
        double avgWordCount = answeredCount > 0 ? totalWordCount / answeredCount : 0;
        double avgConfidence = confidenceCount > 0 ? totalConfidence / confidenceCount : 0;
        double skipRatio = totalAnswers > 0 ? (double) totalSkips / totalAnswers : 0;

        // Determine if early termination should be suggested
        boolean suggest = false;
        if (schedule.getTotalQuestionsAsked() >= minQuestions) {
            // High priority: skip ratio
            if (skipRatio >= skipRatioThreshold) {
                suggest = true;
            }
            // High priority: consecutive skips
            if (maxConsecutiveSkips >= consecutiveSkipThreshold) {
                suggest = true;
            }
            // Medium priority: low word count AND low confidence
            if (avgWordCount < minAvgWordCount && avgConfidence < minConfidenceScore) {
                suggest = true;
            }
            // Medium priority: consecutive poor responses
            if (maxConsecutivePoor >= consecutiveShortAnswerThreshold) {
                suggest = true;
            }
        }

        List<Integer> recentScores = recentScores(candidateEntries);
        Double recentAverage = recentScores.isEmpty()
                ? null
                : Math.round(recentScores.stream().mapToInt(Integer::intValue).average().orElse(0) * 10.0) / 10.0;

        PerformanceSnapshot snapshot = PerformanceSnapshot.builder()
                .totalQuestionsAsked(schedule.getTotalQuestionsAsked())
                .totalSkips(totalSkips)
                .consecutiveSkips(maxConsecutiveSkips)
                .averageWordCount(Math.round(avgWordCount * 10.0) / 10.0)
                .averageConfidenceScore(Math.round(avgConfidence * 10.0) / 10.0)
                .consecutiveShortAnswers(maxConsecutivePoor)
                .skipRatio(Math.round(skipRatio * 100.0) / 100.0)
                .earlyTerminationSuggested(suggest)
                .recentAverageScore(recentAverage)
                .scoredAnswerCount(recentScores.size())
                .difficultyDirection(difficultyDirection(recentScores, recentAverage))
                .build();

        if (suggest) {
            log.info("Early termination suggested for schedule {}: skipRatio={}, consecutiveSkips={}, avgWords={}, avgConfidence={}, consecutivePoor={}",
                    schedule.getId(), snapshot.getSkipRatio(), snapshot.getConsecutiveSkips(),
                    snapshot.getAverageWordCount(), snapshot.getAverageConfidenceScore(), snapshot.getConsecutiveShortAnswers());
        }

        return snapshot;
    }

    /**
     * The interviewer's ratings of the last few answers, oldest first.
     *
     * <p>Only answers that were actually rated. A skip carries no rating and is
     * simply absent rather than counted as a zero — a candidate who skips one
     * question has told you nothing about the difficulty they can handle, and
     * scoring the silence would drag the interview easier on no evidence. That
     * they skipped at all is already handled, more appropriately, by the
     * early-termination signals above.</p>
     */
    private List<Integer> recentScores(List<VoiceConversationEntry> candidateEntries) {
        List<Integer> scores = candidateEntries.stream()
                .map(VoiceConversationEntry::getAnswerScore)
                .filter(java.util.Objects::nonNull)
                .toList();
        int window = Math.max(1, difficultyWindow);
        return scores.size() <= window ? scores : scores.subList(scores.size() - window, scores.size());
    }

    /**
     * Which way to move the next question, or 0 to leave it where it is.
     *
     * <p>Holds until there are enough ratings to mean something, and holds
     * again between the two thresholds. The gap between them is deliberate: with
     * a single cut-off, a candidate hovering around it would be pushed harder
     * and easier on alternate questions.</p>
     */
    private int difficultyDirection(List<Integer> recentScores, Double recentAverage) {
        if (recentAverage == null || recentScores.size() < difficultyMinScoredAnswers) {
            return 0;
        }
        if (recentAverage >= difficultyRaiseAbove) {
            return 1;
        }
        if (recentAverage <= difficultyLowerBelow) {
            return -1;
        }
        return 0;
    }
}
