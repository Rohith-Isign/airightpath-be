package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.enums.TurnKind;

/**
 * The assessment plan the server enforces: one of the job's skills at a time through its levels, no close until every
 * skill and the coding tasks are done, and a report that covers the job's skills and nothing else.
 */
class AssessmentSequenceTest {

    private static final List<String> JOB = List.of("Python", "Machine Learning", "NumPy", "RAG");

    private final InterviewLevelProgression progression = new InterviewLevelProgression();
    private final List<VoiceConversationEntry> entries = new ArrayList<>();

    private void ask(String skill, String level, String content, TurnKind kind) {
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER).content(content)
                .topic("Technical Skills").skill(skill).level(level).turnKind(kind).build());
    }

    private void answer(int score) {
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.CANDIDATE).content("answer")
                .answerScore(score).build());
    }

    @Test
    @DisplayName("tags match the job's own skill names")
    void canonicalNames() {
        assertThat(InterviewLevelProgression.canonical("numpy", JOB)).isEqualTo("NumPy");
        assertThat(InterviewLevelProgression.canonical("Python programming", JOB)).isEqualTo("Python");
        assertThat(InterviewLevelProgression.canonical("Logical Reasoning", JOB)).isNull();
    }

    @Test
    @DisplayName("stays on the skill in progress until it is finished or stopped, then moves to the next")
    void oneSkillAtATime() {
        ask("Python", "BASIC", "q", TurnKind.NEW_QUESTION);
        answer(8);
        assertThat(progression.buildSequenceDirective(entries, JOB))
                .contains("Ask your next question on Python at INTERMEDIATE");

        ask("Python", "INTERMEDIATE", "q", TurnKind.NEW_QUESTION);
        answer(2);
        ask("Python", "INTERMEDIATE", "q", TurnKind.NEW_QUESTION);
        answer(3); // two misses: Python stops at Intermediate
        assertThat(progression.buildSequenceDirective(entries, JOB))
                .contains("Ask your next question on Machine Learning at BASIC");
    }

    @Test
    @DisplayName("the interview may not close while skills or coding tasks remain")
    void completionGate() {
        ask("Python", "BASIC", "q", TurnKind.NEW_QUESTION);
        answer(8);
        String gap = progression.completionGap(entries, JOB, 2);
        assertThat(gap).contains("Machine Learning").contains("NumPy").contains("RAG").contains("coding tasks done: 0");
    }

    @Test
    @DisplayName("a re-asked or corrected coding task is a recovery, not a new task")
    void codingRecoveryCountsOnce() {
        ask("Python", "BASIC", "[CODING] Write a function", TurnKind.NEW_QUESTION);
        answer(4);
        ask("Python", "BASIC", "[CODING] Write a function", TurnKind.REPHRASE);
        answer(8);
        assertThat(InterviewLevelProgression.distinctCodingTasks(entries)).isEqualTo(1);
    }

    @Test
    @DisplayName("the skill report keeps to the job's skills and shows the untested ones as not assessed")
    void reportKeepsToTheJob() {
        VoiceEvaluationResult result = new VoiceEvaluationResult();
        result.setSkillScores(new ArrayList<>(List.of(
                VoiceEvaluationResult.SkillScore.builder().skill("python").score(7).assessed(true).build(),
                VoiceEvaluationResult.SkillScore.builder().skill("Data Cleaning").score(5).assessed(true).build())));

        InterviewEvaluationService.alignSkillsToJob(result, JOB);

        assertThat(result.getSkillScores()).extracting(VoiceEvaluationResult.SkillScore::getSkill)
                .containsExactly("Python", "Machine Learning", "NumPy", "RAG");
        assertThat(result.getSkillScores().get(1).isAssessed()).isFalse();
    }
}
