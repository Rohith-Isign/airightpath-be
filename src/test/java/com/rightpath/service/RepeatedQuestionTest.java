package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.enums.TurnKind;

/** A question already asked is caught when it comes back reworded, padded, or marked as a follow-up. */
class RepeatedQuestionTest {

    private static VoiceConversationEntry asked(String text, TurnKind kind) {
        return VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER).content(text).turnKind(kind).build();
    }

    private static VoiceConversationEntry answer() {
        return VoiceConversationEntry.builder().role(ConversationRole.CANDIDATE).content("answer").build();
    }

    private static final String HASHMAP =
            "How does HashMap handle collisions internally when two keys produce the same bucket index?";

    @Test
    @DisplayName("the same question with words added around it is a repeat")
    void paddedRepeat() {
        assertThat(InterviewContextService.sameQuestion(HASHMAP,
                "In your EventHub project, how does a HashMap handle collisions internally when two different keys "
                        + "produce the same bucket index in practice?")).isTrue();
        assertThat(InterviewContextService.sameQuestion(HASHMAP,
                "How would you design a REST endpoint in Spring Boot to register a user for an event?")).isFalse();
    }

    @Test
    @DisplayName("a follow-up that brings back an earlier, finished question is caught")
    void followUpRepeatingAnOldQuestion() {
        List<VoiceConversationEntry> history = List.of(
                asked(HASHMAP, TurnKind.NEW_QUESTION), answer(),
                asked("How would you design a REST endpoint in Spring Boot to register a user?", TurnKind.NEW_QUESTION),
                answer());
        assertThat(InterviewContextService.repeatedQuestion(history, HASHMAP, true)).isNotNull();
    }

    @Test
    @DisplayName("a follow-up close to the question it presses is not a repeat")
    void followUpOnTheCurrentQuestion() {
        List<VoiceConversationEntry> history = List.of(asked(HASHMAP, TurnKind.NEW_QUESTION), answer());
        assertThat(InterviewContextService.repeatedQuestion(history,
                "And how does HashMap handle collisions internally once a bucket index holds many keys?", true))
                .isNull();
    }
}
