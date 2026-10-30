package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.rightpath.enums.PromptStage;
import com.rightpath.enums.PromptType;

/**
 * The six built-in prompts have to be in the jar and say what the platform relies on. A default that failed to
 * load is silent at startup, so a job with no prompt of its own would find out only when it tried to run.
 */
class DefaultPromptsTest {

    private final DefaultPrompts defaults = new DefaultPrompts();

    @Test
    void allSixSlotsLoad() {
        assertEquals(6, defaults.all().size());
        for (DefaultPrompts.Entry entry : defaults.all()) {
            assertFalse(entry.prompt().isBlank(), entry.promptType() + "/" + entry.promptStage());
        }
    }

    @Test
    void theAptitudePromptSpecifiesTheJsonTheParserReads() {
        String prompt = defaults.find(PromptType.APTITUDE, null).orElseThrow();

        for (String field : new String[] { "\"id\"", "\"question\"", "\"options\"", "\"correctAnswer\"",
                "\"category\"", "\"Difficulty\"" }) {
            assertTrue(prompt.contains(field), "the format block should name " + field);
        }
    }

    @Test
    void theCodingPromptNamesTheFieldsTheParserReadsAndMentionsNoLanguage() {
        String prompt = defaults.find(PromptType.CODING, null).orElseThrow();

        assertTrue(prompt.contains("id,title,question,description,Difficulty,sampleInput,sampleOutput,testCases"));
    }

    @Test
    void bothRoundsOpenOnTheResumeAndEndWithAFormalClose() {
        for (PromptType round : new PromptType[] {
                PromptType.INTERVIEW_L2_TECHNICAL, PromptType.INTERVIEW_L3_BEHAVIORAL }) {
            String prompt = defaults.find(round, PromptStage.START).orElseThrow();

            assertTrue(prompt.contains("{{resumeSummary}}"), round + " should be given the resume");
            assertTrue(prompt.contains("do you have\n   any questions for us"),
                    round + " should ask the closing question");
            assertTrue(prompt.contains("[INTERVIEW_COMPLETE]"), round + " should end on the completion marker");
        }
    }

    @Test
    void theSharedInterviewPromptIsAnsweredWithTheTechnicalRound() {
        assertEquals(
                defaults.find(PromptType.INTERVIEW_L2_TECHNICAL, PromptStage.START),
                defaults.find(PromptType.INTERVIEW, PromptStage.START));
    }

    @Test
    void aSlotWithNothingBuiltInIsEmpty() {
        assertTrue(defaults.find(PromptType.APTITUDE, PromptStage.START).isEmpty());
    }
}
