package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.dto.voice.VoiceEvaluationResult.CategoryScore;
import com.rightpath.entity.EvaluationCategory;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.enums.InterviewRound;
import com.rightpath.util.EvaluationScoreReconciler;

/** The grading rules by round, and a short interview as the whole pipeline sees it. */
class EvaluationRoundRulesTest {

    @Test
    void theTechnicalRoundIsGradedOnCodeAndDepthAndNotAssessedAreasAreNamed() {
        String rules = InterviewEvaluationService.gradingRules(InterviewRound.L2_TECHNICAL);
        assertTrue(rules.contains("Not assessed"));
        assertTrue(rules.contains("code the candidate wrote"));
        assertFalse(rules.contains("behavioural"), "the technical round must not be marked as a behavioural one");
    }

    @Test
    void theBehaviouralRoundIsGradedOnSpecificOwnedExamples() {
        String rules = InterviewEvaluationService.gradingRules(InterviewRound.L3_BEHAVIORAL);
        assertTrue(rules.contains("Not assessed"));
        assertTrue(rules.contains("specific, concrete examples"));
        assertFalse(rules.contains("[RUN OUTPUT]"), "no coding rules in the behavioural round");
    }

    private static CategoryScore area(String name, double score, List<Integer> questions) {
        return CategoryScore.builder().category(name).score(score).weight(0.1).feedback("f")
                .questionNumbers(questions).build();
    }

    /**
     * Seven answers that touched three of eight areas: the shape of the interview that scored a high 6.7 although the
     * required technical skills were never covered. The overall now follows the coverage, and there is no verdict.
     */
    @Test
    void sevenAnswersOverThreeOfEightAreasScoreLowAndGetNoVerdict() {
        List<EvaluationCategory> configured = List.of(
                EvaluationCategory.builder().categoryName("Java").weight(20).build(),
                EvaluationCategory.builder().categoryName("Spring").weight(15).build(),
                EvaluationCategory.builder().categoryName("SQL").weight(15).build(),
                EvaluationCategory.builder().categoryName("Coding").weight(20).build(),
                EvaluationCategory.builder().categoryName("System Design").weight(10).build(),
                EvaluationCategory.builder().categoryName("Testing").weight(5).build(),
                EvaluationCategory.builder().categoryName("Git").weight(5).build(),
                EvaluationCategory.builder().categoryName("Communication").weight(10).build());
        // The grader marked the three areas it asked about generously and the other five were never reached.
        VoiceEvaluationResult result = VoiceEvaluationResult.builder().overallScore(6.7).confidence(0.9)
                .categoryScores(List.of(
                        area("Java", 8, List.of(1, 2, 3)),
                        area("Communication", 8, List.of(1)),
                        area("Git", 8, List.of(4)),
                        area("Spring", 5, List.of()),
                        area("SQL", 5, List.of()),
                        area("Coding", 6, List.of()),
                        area("System Design", 5, List.of()),
                        area("Testing", 5, List.of())))
                .build();

        EvaluationScoreReconciler.reconcile(result, configured);

        // 8 on 35 per cent of the weight, nothing on the rest.
        assertEquals(2.8, result.getOverallScore(), 1e-9);
        assertEquals(8.0, result.getAssessedScore(), 1e-9);
        assertEquals(0.35, result.getCoverage(), 1e-9);

        InterviewReviewTriage triage = new InterviewReviewTriage();
        ReflectionTestUtils.setField(triage, "minAnswers", 5);
        ReflectionTestUtils.setField(triage, "minCoverage", 0.6);
        List<VoiceConversationEntry> answers = IntStream.range(0, 7)
                .mapToObj(i -> VoiceConversationEntry.builder().role(ConversationRole.CANDIDATE)
                        .content("answer " + i).build())
                .toList();
        assertTrue(triage.insufficientEvidence(result, answers), "35 per cent coverage is too little for a verdict");
    }
}
