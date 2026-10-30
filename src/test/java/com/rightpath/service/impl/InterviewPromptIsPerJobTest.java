package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rightpath.entity.JobPrompt;
import com.rightpath.enums.InterviewRound;
import com.rightpath.enums.PromptStage;
import com.rightpath.enums.PromptType;
import com.rightpath.repository.JobPromptRepository;
import com.rightpath.repository.JobPostRepository;
import com.rightpath.service.DefaultPrompts;

/**
 * A job's own prompt wins; the built-in default answers only where it has none.
 *
 * <p>An earlier version had no built-in prompt at all, because a hidden script answered for jobs with nothing
 * saved and an administrator editing prompts saw no change. The defaults are back, but they are the same six texts
 * the Manage AI Prompts screen lists, so nothing answers in secret. These tests hold the order that makes that
 * safe: the round's own prompt, then the shared one, then the default for that round.</p>
 */
class InterviewPromptIsPerJobTest {

    private static final String JOB = "RP-AIML-01";

    private JobPromptRepository promptRepository;
    private JobPromptServiceImpl service;

    @BeforeEach
    void setUp() {
        promptRepository = mock(JobPromptRepository.class);
        service = new JobPromptServiceImpl(promptRepository, mock(JobPostRepository.class), new DefaultPrompts());
    }

    private void nothingConfigured() {
        when(promptRepository.findByJobPrefixAndPromptTypeAndPromptStage(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private void configured(PromptType type, PromptStage stage, String prompt) {
        when(promptRepository.findByJobPrefixAndPromptTypeAndPromptStage(JOB, type, stage))
                .thenReturn(Optional.of(JobPrompt.builder().jobPrefix(JOB).promptType(type)
                        .promptStage(stage).prompt(prompt).build()));
    }

    // ── defaults answer where a job has nothing ───────────────────────

    @Test
    void anUnconfiguredInterviewPromptFallsBackToTheBuiltInDefault() {
        nothingConfigured();

        String prompt = service.getPrompt(JOB, PromptType.INTERVIEW, PromptStage.START);

        // The shared prompt stands for the default round, which is the technical one.
        assertTrue(prompt.contains("L2 technical"), prompt);
    }

    @Test
    void aSlotWithNoDefaultStillRefusesAndSaysWhereToAddIt() {
        nothingConfigured();

        // Aptitude has no START stage, so there is nothing built in to answer with.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.getPrompt(JOB, PromptType.APTITUDE, PromptStage.START));

        assertTrue(refused.getMessage().contains(JOB), "the refusal should name the job");
        assertTrue(refused.getMessage().contains("Manage AI Prompts"),
                "and say where to add the prompt, or the administrator is left guessing");
    }

    @Test
    void aptitudeAndCodingEachHaveADefault() {
        nothingConfigured();

        assertTrue(service.getPrompt(JOB, PromptType.APTITUDE, null).contains("multiple choice"));
        assertTrue(service.getPrompt(JOB, PromptType.CODING, null).contains("coding assessment"));
    }

    @Test
    void eachRoundGetsItsOwnDefaultNotTheOthers() {
        nothingConfigured();

        String l2 = service.getInterviewPrompt(JOB, InterviewRound.L2_TECHNICAL, PromptStage.START);
        String l3 = service.getInterviewPrompt(JOB, InterviewRound.L3_BEHAVIORAL, PromptStage.START);

        assertTrue(l2.contains("L2 technical"), l2);
        assertTrue(l3.contains("behavioural"), l3);
        assertTrue(!l3.contains("L2 technical"), "the behavioural round must not get the technical prompt");
    }

    @Test
    void aMissingSummaryPromptAlsoGetsTheRoundsDefault() {
        nothingConfigured();

        String summary = service.getInterviewPrompt(JOB, InterviewRound.L3_BEHAVIORAL, PromptStage.SUMMARY);

        assertTrue(summary != null && summary.contains("working behaviour"), summary);
    }

    // ── resolution order, which is what makes it dynamic ──────────────

    @Test
    void theRoundsOwnPromptWins() {
        configured(PromptType.INTERVIEW_L2_TECHNICAL, PromptStage.START, "L2 script");
        configured(PromptType.INTERVIEW, PromptStage.START, "shared script");

        assertEquals("L2 script",
                service.getInterviewPrompt(JOB, InterviewRound.L2_TECHNICAL, PromptStage.START));
    }

    @Test
    void aRoundWithNoPromptOfItsOwnUsesTheSharedOneBeforeAnyDefault() {
        // What a job configured before rounds existed has, and the reason the
        // console offers an "Interview (shared)" tab beside the per-round ones.
        when(promptRepository.findByJobPrefixAndPromptTypeAndPromptStage(
                JOB, PromptType.INTERVIEW_L3_BEHAVIORAL, PromptStage.START))
                .thenReturn(Optional.empty());
        configured(PromptType.INTERVIEW, PromptStage.START, "shared script");

        assertEquals("shared script",
                service.getInterviewPrompt(JOB, InterviewRound.L3_BEHAVIORAL, PromptStage.START));
    }

    @Test
    void aSavedPromptAlwaysBeatsTheDefault() {
        configured(PromptType.APTITUDE, null, "my own aptitude prompt");

        assertEquals("my own aptitude prompt", service.getPrompt(JOB, PromptType.APTITUDE, null));
    }
}
