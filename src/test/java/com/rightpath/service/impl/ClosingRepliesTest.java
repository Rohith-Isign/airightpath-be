package com.rightpath.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The close of the interview, and the English check: "yes" to "any questions?" invites the question instead of ending
 * the interview, and an answer that is plainly English is never marked as not in English.
 */
class ClosingRepliesTest {

    @Test
    @DisplayName("a yes without the question is recognised; a no, or a question itself, is not")
    void yesWithoutQuestion() {
        assertThat(VoiceInterviewServiceImpl.saysTheyHaveAQuestion("Yes.")).isTrue();
        assertThat(VoiceInterviewServiceImpl.saysTheyHaveAQuestion("Yeah, I have one question")).isTrue();
        assertThat(VoiceInterviewServiceImpl.saysTheyHaveAQuestion("No, nothing. Thank you.")).isFalse();
        assertThat(VoiceInterviewServiceImpl.saysTheyHaveAQuestion("Nope")).isFalse();
    }

    @Test
    @DisplayName("the invitation is itself read as part of the close, so the question that follows is answered")
    void invitationStaysInTheClose() throws Exception {
        java.lang.reflect.Method isClosing = VoiceInterviewServiceImpl.class
                .getDeclaredMethod("isClosingQuestion", String.class);
        isClosing.setAccessible(true);
        assertThat((boolean) isClosing.invoke(null, VoiceInterviewServiceImpl.INVITE_QUESTION)).isTrue();
    }

    @Test
    @DisplayName("an English answer is English even when off topic; romanised Telugu is not")
    void englishCheck() {
        assertThat(VoiceInterviewServiceImpl.looksEnglish(
                "SpringBoot is a Java-based framework used to develop backend applications and REST APIs easily."))
                .isTrue();
        assertThat(VoiceInterviewServiceImpl.looksEnglish("nenu aa project lo backend work chesanu andi")).isFalse();
    }

    @Test
    @DisplayName("a non-English answer gets the fixed notice first, after the coding tag, and only once")
    void englishNotice() {
        com.rightpath.dto.voice.ParsedInterviewReply reply = new com.rightpath.dto.voice.ParsedInterviewReply(
                "Could you explain closures again?", 0, com.rightpath.enums.TurnKind.REPHRASE, null, false);
        String spoken = VoiceInterviewServiceImpl.withEnglishNotice(reply).spokenText();
        assertThat(spoken).startsWith(VoiceInterviewServiceImpl.ENGLISH_NOTICE).endsWith("closures again?");

        com.rightpath.dto.voice.ParsedInterviewReply coding = new com.rightpath.dto.voice.ParsedInterviewReply(
                "[CODING] Write the function again.", 0, com.rightpath.enums.TurnKind.REPHRASE, null, false);
        assertThat(VoiceInterviewServiceImpl.withEnglishNotice(coding).spokenText())
                .startsWith("[CODING] " + VoiceInterviewServiceImpl.ENGLISH_NOTICE);

        assertThat(VoiceInterviewServiceImpl.withEnglishNotice(VoiceInterviewServiceImpl.withEnglishNotice(reply))
                .spokenText()).isEqualTo(spoken);
    }
}
