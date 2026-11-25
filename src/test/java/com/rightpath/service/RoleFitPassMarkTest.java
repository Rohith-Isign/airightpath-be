package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.VoiceEvaluationResult;

/**
 * Pass or fail is decided against the role: 60% of the role's requirements shown (core ones counting double), and
 * every core skill shown at least in part. A 62% candidate was failed on the grader's say-so with no threshold anyone
 * could name; answering a few questions well must not pass either.
 */
class RoleFitPassMarkTest {

    private static final List<String> JOB = List.of("Python", "Machine Learning", "RAG");

    private static VoiceEvaluationResult.Concept req(String skill, String importance, String status) {
        return VoiceEvaluationResult.Concept.builder().concept(skill + " requirement").requirement("SKILL")
                .skill(skill).importance(importance).status(status).area("OTHER").build();
    }

    private static VoiceEvaluationResult result(String recommendation, List<VoiceEvaluationResult.Concept> concepts) {
        VoiceEvaluationResult r = new VoiceEvaluationResult();
        r.setOverallScore(5.0);
        r.setRecommendation(recommendation);
        r.setExpectedConcepts(new ArrayList<>(concepts));
        InterviewEvaluationService.applyScope(r);
        return r;
    }

    @Test
    void atThePassMarkWithEveryCoreSkillShownIsAPass() {
        VoiceEvaluationResult r = result("LEAN_NO_HIRE", List.of(
                req("Python", "CORE", "CORRECT"), req("Python", "SUPPORTING", "CORRECT"),
                req("Machine Learning", "CORE", "CORRECT"), req("Machine Learning", "SUPPORTING", "NOT_ASKED"),
                req("RAG", "CORE", "PARTIAL"), req("RAG", "SUPPORTING", "NOT_ASKED")));
        // credit: 2 + 1 + 2 + 0 + 1 + 0 = 6 of 9 = 66.7%
        InterviewEvaluationService.decide(r, 60, JOB);
        assertThat(r.getScopeScore()).isEqualTo(66.7);
        assertThat(r.getMeetsPassMark()).isTrue();
        assertThat(r.getRecommendation()).isEqualTo("LEAN_HIRE");
        assertThat(r.getResultReason()).contains("Role fit 67% against a pass mark of 60%").contains("Passed");
    }

    @Test
    void answeringTheAskedQuestionsWellIsNotEnoughWhenMostOfTheRoleWasNeverShown() {
        VoiceEvaluationResult r = result("HIRE", List.of(
                req("Python", "CORE", "CORRECT"), req("Python", "SUPPORTING", "CORRECT"),
                req("Machine Learning", "CORE", "NOT_ASKED"), req("Machine Learning", "SUPPORTING", "NOT_ASKED"),
                req("RAG", "CORE", "PARTIAL"), req("RAG", "SUPPORTING", "NOT_ASKED")));
        InterviewEvaluationService.decide(r, 60, JOB);
        assertThat(r.getMeetsPassMark()).isFalse();
        assertThat(r.getRecommendation()).isEqualTo("LEAN_NO_HIRE");
        assertThat(r.getMissingCoreSkills()).containsExactly("Machine Learning");
    }

    @Test
    void aCoreSkillNotShownAtAllFailsEvenAboveThePassMark() {
        VoiceEvaluationResult r = result("HIRE", List.of(
                req("Python", "CORE", "CORRECT"), req("Python", "CORE", "CORRECT"),
                req("Machine Learning", "CORE", "CORRECT"), req("Machine Learning", "CORE", "CORRECT"),
                req("RAG", "CORE", "INCORRECT"), req("Python", "SUPPORTING", "CORRECT")));
        // credit 2+2+2+2+0+1 = 9 of 11 = 81.8%, but RAG was not shown at all
        InterviewEvaluationService.decide(r, 60, JOB);
        assertThat(r.getScopeScore()).isGreaterThan(60.0);
        assertThat(r.getMeetsPassMark()).isFalse();
        assertThat(r.getResultReason()).contains("Core skill not shown: RAG").contains("every core skill must be shown");
    }
}
