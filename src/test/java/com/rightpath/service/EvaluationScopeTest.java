package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.VoiceEvaluationResult;

/**
 * The score is measured against everything the interview should have covered, not only what it reached.
 */
class EvaluationScopeTest {

    private static VoiceEvaluationResult withConcepts(String... statuses) {
        List<VoiceEvaluationResult.Concept> concepts = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            concepts.add(VoiceEvaluationResult.Concept.builder()
                    .concept("Concept " + i).area("BACKEND").status(statuses[i]).questionNumbers(List.of()).build());
        }
        VoiceEvaluationResult result = new VoiceEvaluationResult();
        result.setOverallScore(9.0); // what the weighted areas said: high, because what was asked went well
        result.setExpectedConcepts(concepts);
        return result;
    }

    @Test
    @DisplayName("10 concepts planned, 5 asked and all answered well: 50 out of 100, not 100")
    void halfTheScopeIsHalfTheScore() {
        VoiceEvaluationResult result = withConcepts(
                "CORRECT", "CORRECT", "CORRECT", "CORRECT", "CORRECT",
                "NOT_ASKED", "NOT_ASKED", "NOT_ASKED", "NOT_ASKED", "NOT_ASKED");

        InterviewEvaluationService.applyScope(result);

        assertThat(result.getScopeScore()).isEqualTo(50.0);
        assertThat(result.getOverallScore()).isEqualTo(5.0);
        assertThat(result.getAssessedScore()).isEqualTo(9.0); // how they did on what was asked is kept apart
        assertThat(result.getCoverage()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a partly right answer earns half; a wrong one earns nothing")
    void partialAndIncorrect() {
        VoiceEvaluationResult result = withConcepts("CORRECT", "PARTIAL", "INCORRECT", "NOT_ASKED");

        InterviewEvaluationService.applyScope(result);

        assertThat(result.getScopeScore()).isEqualTo(37.5);
        assertThat(result.getCoverage()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("too short a list is not a scope: the weighted area score stands")
    void tooFewConcepts() {
        VoiceEvaluationResult result = withConcepts("CORRECT", "NOT_ASKED");

        InterviewEvaluationService.applyScope(result);

        assertThat(result.getScopeScore()).isNull();
        assertThat(result.getOverallScore()).isEqualTo(9.0);
    }
}
