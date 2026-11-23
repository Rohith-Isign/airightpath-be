package com.rightpath.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every interview opens with the candidate introducing themselves, never with a technical question. */
class InterviewOpeningTest {

    @Test
    @DisplayName("an introduction request is recognised in its usual wordings")
    void introductions() {
        assertThat(VoiceInterviewServiceImpl.asksForIntroduction(
                "Could you start by introducing yourself? Tell me about your background.")).isTrue();
        assertThat(VoiceInterviewServiceImpl.asksForIntroduction("Tell me a bit about yourself and your projects."))
                .isTrue();
        assertThat(VoiceInterviewServiceImpl.asksForIntroduction(VoiceInterviewServiceImpl.INTRODUCTION_QUESTION))
                .isTrue();
    }

    @Test
    @DisplayName("a technical first question is not an introduction, so the standard opening replaces it")
    void technicalOpening() {
        assertThat(VoiceInterviewServiceImpl.asksForIntroduction(
                "Could you explain the core principles of Object-Oriented Programming in Java?")).isFalse();
        assertThat(VoiceInterviewServiceImpl.asksForIntroduction("[CODING] Write a function that reverses a string."))
                .isFalse();
    }
}
