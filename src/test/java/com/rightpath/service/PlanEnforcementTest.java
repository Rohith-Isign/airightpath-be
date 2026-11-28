package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.ParsedInterviewReply;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.enums.TurnKind;
import com.rightpath.service.impl.VoiceInterviewServiceImplPlanAccess;

/**
 * The plan with its defaults: two correct answers per level, a coding task at each level for every technical skill, a
 * new skill at Basic, and a question that does not match the plan recognised so it can be sent back.
 */
class PlanEnforcementTest {

    private static final List<String> JOB = List.of("Python", "Machine Learning", "RAG");

    private final InterviewLevelProgression progression = new InterviewLevelProgression();

    {
        // Two questions a level keeps these short; the five-question default is pinned in InterviewLevelProgressionTest.
        progression.setLevelRule(2, 1.0);
    }
    private final List<VoiceConversationEntry> entries = new ArrayList<>();

    private void ask(String skill, String level, boolean coding) {
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER)
                .content(coding ? "[CODING] task" : "question").skill(skill).level(level)
                .turnKind(TurnKind.NEW_QUESTION).build());
    }

    private void answer(int score, boolean code) {
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.CANDIDATE).content("answer")
                .codeContent(code ? "code" : null).answerScore(score).build());
    }

    @Test
    @DisplayName("every technical skill needs coding; soft skills do not")
    void codingForEveryTechnicalSkill() {
        assertThat(progression.isCodingSkill("Machine Learning")).isTrue();
        assertThat(progression.isCodingSkill("RAG")).isTrue();
        assertThat(progression.isCodingSkill("Communication")).isFalse();
    }

    @Test
    @DisplayName("a level takes a question and its coding task, two correct answers, before the skill moves up")
    void twoAnswersPerLevelOneOfThemCoding() {
        assertThat(progression.expectedNext(entries, JOB))
                .isEqualTo(new InterviewLevelProgression.Expected("Python", "BASIC", false));

        ask("Python", "BASIC", false);
        answer(8, false);
        // One correct answer is not enough, and the level's coding task comes next.
        assertThat(progression.expectedNext(entries, JOB))
                .isEqualTo(new InterviewLevelProgression.Expected("Python", "BASIC", true));

        ask("Python", "BASIC", true);
        answer(9, true);
        assertThat(progression.expectedNext(entries, JOB))
                .isEqualTo(new InterviewLevelProgression.Expected("Python", "INTERMEDIATE", false));
    }

    @Test
    @DisplayName("a skill stays one block: it is not left at Basic and come back to later")
    void oneBlockPerSkill() {
        ask("Python", "BASIC", false);
        answer(8, false);
        ask("Python", "BASIC", true);
        answer(8, true);
        // Plenty of questions left: Python carries on, deeper, rather than handing over and returning later.
        assertThat(progression.expectedNext(entries, JOB, 40).skill()).isEqualTo("Python");
    }

    @Test
    @DisplayName("no skill may take the questions the later skills need")
    void eachSkillGetsAFairShare() {
        // A limit of 12, 6 kept for the introduction, resume and close: 6 left for 3 skills, so 2 each (one level).
        ask("Python", "BASIC", false);
        answer(8, false);
        ask("Python", "BASIC", true);
        answer(8, true);
        assertThat(progression.expectedNext(entries, JOB, 12))
                .isEqualTo(new InterviewLevelProgression.Expected("Machine Learning", "BASIC", false));
        assertThat(progression.unfinished(entries, JOB, 12)).containsExactly("Machine Learning", "RAG");
    }

    @Test
    @DisplayName("a skill that stops early hands its unused questions to the skills after it")
    void unusedShareMovesOn() {
        ask("Python", "BASIC", true);
        answer(2, true); // stopped at Basic after one question
        // 20 - 6 - 1 = 13 left for Machine Learning and RAG: 6 each, not the 4 an even three-way split would give.
        String[] levels = {"BASIC", "BASIC", "INTERMEDIATE", "INTERMEDIATE", "ADVANCED"};
        for (int i = 0; i < levels.length; i++) {
            boolean coding = i == 1 || i == 2;
            ask("Machine Learning", levels[i], coding);
            answer(8, coding);
        }
        assertThat(progression.expectedNext(entries, JOB, 20).skill()).isEqualTo("Machine Learning");
    }

    @Test
    @DisplayName("a skill that stops at Basic is left; the others still get their turn")
    void stoppedSkillDoesNotBlockTheRest() {
        ask("Python", "BASIC", true);
        answer(2, true); // two questions a level, both needed: one miss stops Python at Basic
        assertThat(progression.notStarted(entries, JOB)).containsExactly("Machine Learning", "RAG");
        assertThat(progression.expectedNext(entries, JOB).skill()).isEqualTo("Machine Learning");
    }

    @Test
    @DisplayName("a question on another skill, or at the wrong level, or without its owed coding task, is a mismatch")
    void mismatches() {
        InterviewLevelProgression.Expected expected = new InterviewLevelProgression.Expected("Python", "INTERMEDIATE", false);
        ParsedInterviewReply jumped = new ParsedInterviewReply("Explain overfitting.", 8, TurnKind.NEW_QUESTION,
                "Technical Skills", false, "INTERMEDIATE", false, "Machine Learning", false, "overfitting");
        assertThat(VoiceInterviewServiceImplPlanAccess.mismatch(expected, jumped, JOB)).contains("Python");

        ParsedInterviewReply right = new ParsedInterviewReply("Explain decorators.", 8, TurnKind.NEW_QUESTION,
                "Technical Skills", false, "INTERMEDIATE", false, "python", false, "decorators");
        assertThat(VoiceInterviewServiceImplPlanAccess.mismatch(expected, right, JOB)).isNull();

        InterviewLevelProgression.Expected coding = new InterviewLevelProgression.Expected("Python", "BASIC", true);
        ParsedInterviewReply theory = new ParsedInterviewReply("What is a list?", 8, TurnKind.NEW_QUESTION,
                "Technical Skills", false, "BASIC", false, "Python", false, "lists");
        assertThat(VoiceInterviewServiceImplPlanAccess.mismatch(coding, theory, JOB)).contains("coding task");
    }
}
