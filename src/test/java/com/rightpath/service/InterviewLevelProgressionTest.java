package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;

/**
 * The server, not the model, decides when a skill has earned the next level. These pin the rule: 6/10 or more is
 * correct, one correct answer moves a skill up, two that are not correct stop it there.
 */
class InterviewLevelProgressionTest {

    private final InterviewLevelProgression progression = new InterviewLevelProgression();

    {
        // These pin the one-answer rule they were written for; the default is now two (see AssessmentSequenceTest).
        progression.setPassesToAdvance(1);
    }
    private final List<VoiceConversationEntry> entries = new ArrayList<>();

    private void ask(String topic, String level) {
        entries.add(VoiceConversationEntry.builder()
                .role(ConversationRole.INTERVIEWER).content("question").topic(topic).level(level).build());
    }

    private void answer(Integer score) {
        entries.add(VoiceConversationEntry.builder()
                .role(ConversationRole.CANDIDATE).content("answer").answerScore(score).build());
    }

    private void skip() {
        entries.add(VoiceConversationEntry.builder()
                .role(ConversationRole.CANDIDATE).content("[NO RESPONSE - SKIPPED]").build());
    }

    @Test
    @DisplayName("6/10 or more is correct, below is not, and an unrated answer is unknown")
    void passMark() {
        assertThat(progression.isCorrect(6)).isTrue();
        assertThat(progression.isCorrect(5)).isFalse();
        assertThat(progression.isCorrect(null)).isNull();
    }

    @Test
    @DisplayName("a correct answer moves the skill up one level")
    void correctAnswerAdvances() {
        ask("Java", "BASIC");
        answer(8);

        Map<String, InterviewLevelProgression.SkillState> skills = progression.compute(entries);
        assertThat(skills.get("Java").currentLevel()).isEqualTo("INTERMEDIATE");
        assertThat(skills.get("Java").highestPassed()).isEqualTo("BASIC");
    }

    @Test
    @DisplayName("one slip keeps the level; a second answer that is not correct stops the skill there")
    void twoMissesStop() {
        ask("Java", "BASIC");
        answer(8);
        ask("Java", "INTERMEDIATE");
        answer(4);
        assertThat(progression.compute(entries).get("Java").isStopped()).isFalse();

        ask(null, "INTERMEDIATE"); // a follow-up stays on the skill last named
        skip();
        InterviewLevelProgression.SkillState java = progression.compute(entries).get("Java");
        assertThat(java.isStopped()).isTrue();
        assertThat(java.currentLevel()).isEqualTo("INTERMEDIATE");
        assertThat(java.highestPassed()).isEqualTo("BASIC");
    }

    @Test
    @DisplayName("passing Expert finishes the skill, and skills progress independently")
    void independentSkills() {
        for (String level : InterviewLevelProgression.LEVELS) {
            ask("SQL", level);
            answer(9);
        }
        ask("AWS", "BASIC");
        answer(3);

        Map<String, InterviewLevelProgression.SkillState> skills = progression.compute(entries);
        assertThat(skills.get("SQL").isFinished()).isTrue();
        assertThat(skills.get("AWS").currentLevel()).isEqualTo("BASIC");
        assertThat(skills.get("AWS").isStopped()).isFalse();
    }

    @Test
    @DisplayName("the directive names each skill's next level, and is absent before anything is rated")
    void directive() {
        assertThat(progression.buildDirective(entries)).isNull();
        ask("Java", "BASIC");
        answer(7);
        assertThat(progression.buildDirective(entries)).contains("Java: ask at INTERMEDIATE next");
    }

    @Test
    @DisplayName("levels progress per skill, not per category: Java moves up while SQL in the same category stops")
    void perSkill() {
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER).content("q")
                .topic("Technical Skills").skill("Java").level("BASIC").build());
        answer(8);
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER).content("q")
                .topic("Technical Skills").skill("SQL").level("BASIC").build());
        answer(2);
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER).content("q")
                .topic("Technical Skills").skill("SQL").level("BASIC").build());
        answer(3);

        Map<String, InterviewLevelProgression.SkillState> skills = progression.compute(entries);
        assertThat(skills.get("Java").currentLevel()).isEqualTo("INTERMEDIATE");
        assertThat(skills.get("SQL").isStopped()).isTrue();
        assertThat(skills).doesNotContainKey("Technical Skills");
    }
}
