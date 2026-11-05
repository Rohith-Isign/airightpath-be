package com.rightpath.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.CompletionReason;
import com.rightpath.enums.ConversationRole;

/**
 * Decides which finished interviews a person should look at before the result
 * is acted on.
 *
 * <p>Every interview produced a score and a hire recommendation, and nothing
 * distinguished a verdict drawn from twelve full answers from one drawn from
 * three words and a hang-up. Both arrived in the results table looking equally
 * settled. A model asked to grade a thin transcript does not hedge — it writes
 * the same fluent paragraph either way — so "the AI was unsure" is not
 * something a reviewer can read off the summary.</p>
 *
 * <p>The model's own stated confidence is one input, and the weakest: it is a
 * self-assessment, and a model that fabricates a candidate will happily report
 * that it is sure. The rest are facts about what happened — how much was
 * actually said, how the interview ended, whether the candidate tried to work
 * the interviewer — which do not depend on the grader being honest about
 * itself.</p>
 */
@Service
public class InterviewReviewTriage {

    /**
     * Recommendations that are borderline by their own wording.
     *
     * <p>"Lean" is the model saying it could go either way. That is precisely
     * the decision worth a person's attention, and precisely the one that gets
     * rubber-stamped when it arrives looking like any other.</p>
     */
    private static final Set<String> BORDERLINE_RECOMMENDATIONS = Set.of("LEAN_HIRE", "LEAN_NO_HIRE");

    /** What a skipped question is recorded as — not an answer. */
    private static final String SKIPPED_CONTENT = "[NO RESPONSE - SKIPPED]";

    /**
     * Below this stated confidence, a person looks at it.
     *
     * <p>0.7 rather than 0.5. The cost of a needless review is a few minutes of
     * a recruiter's time; the cost of a missed one is a hiring decision taken on
     * a verdict the grader itself was unsure of.</p>
     */
    @Value("${interview.review.min-confidence:0.7}")
    private double minConfidence;

    /** Fewer real answers than this and there is not enough to grade on. */
    @Value("${interview.review.min-answers:5}")
    private int minAnswers;

    /**
     * The share of the scoring areas, by weight, that the questions asked must have touched before the interview is
     * given a pass or a fail. Below it the result is left undecided for a person: a verdict on a candidate who was
     * asked about half the syllabus is a verdict on the half.
     */
    @Value("${interview.review.min-coverage:0.6}")
    private double minCoverage;

    /**
     * The share of the scoring areas (by weight) that at least one question was asked about, 0..1. A result from
     * before questions were tied to areas carries no such record and counts as fully covered.
     */
    public double coverage(VoiceEvaluationResult result) {
        if (result == null || result.getCategoryScores() == null || result.getCategoryScores().isEmpty()) {
            return 1.0;
        }
        double total = 0;
        double covered = 0;
        for (VoiceEvaluationResult.CategoryScore category : result.getCategoryScores()) {
            double weight = Math.max(category.getWeight(), 0);
            total += weight;
            List<Integer> asked = category.getQuestionNumbers();
            if (asked == null || !asked.isEmpty()) {
                covered += weight;
            }
        }
        return total <= 0 ? 1.0 : covered / total;
    }

    /**
     * Whether there is too little evidence to call the interview a pass or a fail: too few answers, or too much of the
     * syllabus never asked about. The score and the notes are still shown; only the verdict is withheld.
     */
    public boolean insufficientEvidence(VoiceEvaluationResult result, List<VoiceConversationEntry> entries) {
        return countAnswers(entries) < minAnswers || coverage(result) < minCoverage;
    }

    /**
     * Whether a review is needed, and why.
     *
     * <p>Reasons are collected rather than short-circuited. Two weak signals
     * together are a different conversation from one, and a reviewer opening
     * the interview should see everything that flagged it — not just whichever
     * check happened to run first.</p>
     */
    public List<String> reviewReasons(CandidateInterviewSchedule schedule,
                                      VoiceEvaluationResult result,
                                      List<VoiceConversationEntry> entries) {
        List<String> reasons = new ArrayList<>();

        long answers = countAnswers(entries);
        if (answers == 0) {
            // Already recorded as not assessable, but it still has to reach a
            // person: "no score" is a fact, and what it means — a no-show, a
            // broken microphone, a withdrawal — is not something this can know.
            reasons.add("The candidate did not answer any questions, so there is nothing to score.");
        } else if (answers < minAnswers) {
            reasons.add("Only " + answers + " question(s) were answered — too little to judge on.");
        }

        if (result != null && coverage(result) < minCoverage) {
            reasons.add(String.format(
                    "Questions touched only %.0f%% of the scoring areas, so no pass or fail has been decided.",
                    coverage(result) * 100));
        }

        if (result != null) {
            Double confidence = result.getConfidence();
            if (confidence == null) {
                reasons.add("The evaluator did not state how confident it was.");
            } else if (confidence < minConfidence) {
                reasons.add(String.format(
                        "The evaluator was only %.0f%% confident in this assessment.", confidence * 100));
            }

            String recommendation = result.getRecommendation();
            if (recommendation != null && BORDERLINE_RECOMMENDATIONS.contains(recommendation)) {
                reasons.add("The recommendation is borderline (" + recommendation.replace('_', ' ').toLowerCase()
                        + ") — it could reasonably go either way.");
            }
        }

        if (schedule.getCompletionReason() == CompletionReason.EARLY_TERMINATION_POOR_PERFORMANCE) {
            reasons.add("The interview was ended early for poor performance, so the candidate was not asked everything.");
        }
        if (schedule.getCompletionReason() == CompletionReason.TIMEOUT) {
            reasons.add("The interview ran out of time rather than finishing, so the transcript may stop mid-answer.");
        }
        if (schedule.getCompletionReason() == CompletionReason.PROCTORING_VIOLATION) {
            reasons.add("The interview was stopped on proctoring warnings — the result rests on a decision a person should confirm.");
        }

        if (hasInjectionAttempt(entries)) {
            reasons.add("The candidate tried to instruct the interviewer. It had no effect, but the conduct is worth a look.");
        }

        return reasons;
    }

    /** Candidate turns that actually said something. Skips are not answers. */
    private long countAnswers(List<VoiceConversationEntry> entries) {
        if (entries == null) {
            return 0;
        }
        return entries.stream()
                .filter(entry -> entry.getRole() == ConversationRole.CANDIDATE)
                .map(VoiceConversationEntry::getContent)
                .filter(content -> content != null && !content.isBlank())
                .filter(content -> !SKIPPED_CONTENT.equals(content.trim()))
                .count();
    }

    private boolean hasInjectionAttempt(List<VoiceConversationEntry> entries) {
        return entries != null && entries.stream()
                .anyMatch(entry -> Boolean.TRUE.equals(entry.getInjectionSuspected()));
    }
}
