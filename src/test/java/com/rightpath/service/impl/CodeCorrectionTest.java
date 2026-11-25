package com.rightpath.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.service.InterviewConductPolicy;
import com.rightpath.util.InterviewReplyParser;
import com.rightpath.util.PromptInjectionGuard;

/** A request to correct submitted code opens the notepad; a spoken question marked as theory does not. */
class CodeCorrectionTest {

    private final InterviewReplyParser parser =
            new InterviewReplyParser(new InterviewConductPolicy(new PromptInjectionGuard()));

    @Test
    @DisplayName("correction requests are recognised in their usual wordings")
    void recognised() {
        assertThat(VoiceInterviewServiceImpl.asksToCorrectCode(
                "Your loop checks the position, not the value. Could you correct your function?")).isTrue();
        assertThat(VoiceInterviewServiceImpl.asksToCorrectCode("Can you fix the code so it handles an empty list?"))
                .isTrue();
        assertThat(VoiceInterviewServiceImpl.asksToCorrectCode("What is a closure in JavaScript?")).isFalse();
    }

    @Test
    @DisplayName("an untagged correction request opens the notepad")
    void untaggedCorrectionOpensNotepad() {
        assertThat(parser.parse("[SCORE:4] Close. Could you correct your function so it skips negatives?")
                .spokenText()).startsWith("[CODING]");
    }

    @Test
    @DisplayName("a spoken question the model marked as theory stays spoken")
    void theoryStaysSpoken() {
        assertThat(parser.parse("[THEORY] How would you fix a slow SQL query in production?").spokenText())
                .doesNotStartWith("[CODING]");
    }
}
