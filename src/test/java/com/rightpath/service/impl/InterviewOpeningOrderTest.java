package com.rightpath.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.enums.TurnKind;
import com.rightpath.service.InterviewContextService;

/**
 * The opening: "hello" is not an introduction and is asked again; re-asks are not new questions, so the resume
 * questions that follow are counted from the first real one; and a question is not asked twice, however it is worded.
 */
class InterviewOpeningOrderTest {

    private final List<VoiceConversationEntry> history = new ArrayList<>();

    private void interviewer(String text, TurnKind kind) {
        history.add(VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER).content(text).turnKind(kind)
                .build());
    }

    private void candidate(String text) {
        history.add(VoiceConversationEntry.builder().role(ConversationRole.CANDIDATE).content(text).build());
    }

    @Test
    void aGreetingIsNotAnIntroduction() {
        assertThat(VoiceInterviewServiceImpl.wordCount("hello")).isLessThan(VoiceInterviewServiceImpl.INTRODUCTION_MIN_WORDS);
        assertThat(VoiceInterviewServiceImpl.wordCount("Hi, I am Praveen.")).isLessThan(
                VoiceInterviewServiceImpl.INTRODUCTION_MIN_WORDS);
        assertThat(VoiceInterviewServiceImpl.wordCount("I am Praveen, I studied computer science at JNTU and for the "
                + "last two years I have worked on a recommendation system in Python, building the data pipeline and "
                + "training the models")).isGreaterThanOrEqualTo(VoiceInterviewServiceImpl.INTRODUCTION_MIN_WORDS);
        // Both re-asks are still recognised as asking for the introduction.
        VoiceInterviewServiceImpl.INTRODUCTION_REASKS.forEach(reask ->
                assertThat(VoiceInterviewServiceImpl.asksForIntroduction(reask.replaceAll("\\[[A-Z]+]", ""))).isTrue());
    }

    @Test
    void reAsksAndFollowUpsAreNotNewQuestions() {
        interviewer("Hello Praveen, welcome. Could you please introduce yourself?", null);
        candidate("hello");
        interviewer("Thank you. Could you please introduce yourself properly?", TurnKind.REPHRASE);
        candidate("I am Praveen ...");
        assertThat(InterviewContextService.questionsAfterIntroduction(history)).isZero();

        interviewer("In your recommendation project, how did you choose the features?", TurnKind.NEW_QUESTION);
        candidate("...");
        interviewer("Why that approach and not another?", TurnKind.FOLLOW_UP);
        assertThat(InterviewContextService.questionsAfterIntroduction(history)).isEqualTo(1);
    }

    @Test
    void theSameQuestionReworded() {
        interviewer("Good.\n\nCan you explain how you would handle exceptions when reading from a file in Python, so "
                + "the program does not crash?", TurnKind.NEW_QUESTION);

        assertThat(InterviewContextService.repeatedQuestion(history, "Nice answer.\n\nHow would you handle exceptions "
                + "when reading a file in Python so that the program does not crash?")).isNotNull();
        assertThat(InterviewContextService.repeatedQuestion(history, "Thanks.\n\nHow does a Python dictionary store "
                + "its keys, and what happens on a hash collision?")).isNull();
    }
}
