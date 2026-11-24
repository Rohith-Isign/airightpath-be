package com.rightpath.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.ParsedInterviewReply;
import com.rightpath.enums.TurnKind;
import com.rightpath.service.InterviewLevelProgression;

/**
 * "Let's shift our focus to machine learning now" went out tagged [SKILL:Python]. It passed the plan check as a
 * Python question, the candidate's skip counted against Python, and the next question jumped back to Python.
 */
class AnnouncedSkillSwitchTest {

    private static final List<String> JOB = List.of("Python", "Machine Learning", "SQL");

    private static ParsedInterviewReply question(String text, String skill, String level) {
        return new ParsedInterviewReply(text, 9, TurnKind.NEW_QUESTION, "Technical Skills", false, level, false, skill,
                false, "feature selection");
    }

    @Test
    void readsTheSkillAQuestionSaysItMovesTo() {
        assertThat(VoiceInterviewServiceImpl.announcedSkillSwitch(
                "That's a comprehensive explanation. Let's shift our focus to machine learning now. Can you describe "
                        + "how you would select features?", JOB)).isEqualTo("Machine Learning");
        assertThat(VoiceInterviewServiceImpl.announcedSkillSwitch(
                "No worries, let's move on. Returning to Python, how would you handle exceptions?", JOB))
                .isEqualTo("Python");
        assertThat(VoiceInterviewServiceImpl.announcedSkillSwitch("Now let's talk about SQL. What is a join?", JOB))
                .isEqualTo("SQL");
    }

    @Test
    void aMentionIsNotASwitch() {
        // A Python question that mentions machine learning in passing is still a Python question.
        assertThat(VoiceInterviewServiceImpl.announcedSkillSwitch(
                "In Python, how would you load a CSV to prepare data for a machine learning model?", JOB)).isNull();
        assertThat(VoiceInterviewServiceImpl.announcedSkillSwitch("Explain decorators.", JOB)).isNull();
    }

    @Test
    void aQuestionTaggedPythonThatMovesToMachineLearningIsSentBack() {
        InterviewLevelProgression.Expected python = new InterviewLevelProgression.Expected("Python", "ADVANCED", false);
        ParsedInterviewReply mislabelled = question(
                "Let's shift our focus to machine learning now. How would you select features for a model?",
                "Python", "ADVANCED");
        assertThat(VoiceInterviewServiceImpl.planMismatch(python, mislabelled, JOB))
                .contains("moves on to Machine Learning").contains("in progress is Python");
    }

    @Test
    void anHonestSwitchWhenThePlanCallsForItPasses() {
        InterviewLevelProgression.Expected ml = new InterviewLevelProgression.Expected("Machine Learning", "BASIC", false);
        ParsedInterviewReply next = question(
                "Let's shift our focus to machine learning now. What is overfitting?", "Machine Learning", "BASIC");
        assertThat(VoiceInterviewServiceImpl.planMismatch(ml, next, JOB)).isNull();
    }
}
