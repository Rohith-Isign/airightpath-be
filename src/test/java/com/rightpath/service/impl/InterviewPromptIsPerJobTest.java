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

/**
 * Interview prompts come from the job, and from nowhere else.
 *
 * <p>{@code INTERVIEW}/{@code START} used to fall back to a system prompt
 * compiled into the jar from {@code interview-prompts.properties}. Two things
 * followed: every job with nothing configured interviewed with the same hidden
 * script, and an administrator who edited prompts in the console saw no change
 * for a job whose prompt they had never saved — the fallback answered instead,
 * silently, with only a log line to say so.</p>
 *
 * <p>These tests hold that fallback gone. It is the kind of convenience that
 * gets reintroduced to make an error go away, so the absence is asserted rather
 * than assumed, and the refusal is checked to name what the administrator has
 * to do.</p>
 */
class InterviewPromptIsPerJobTest {

    private static final String JOB = "RP-AIML-01";

    private JobPromptRepository promptRepository;
    private JobPromptServiceImpl service;

    @BeforeEach
    void setUp() {
        promptRepository = mock(JobPromptRepository.class);
        service = new JobPromptServiceImpl(promptRepository, mock(JobPostRepository.class));
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

    // ── no built-in prompt remains ────────────────────────────────────

    @Test
    void anUnconfiguredInterviewPromptIsRefusedRatherThanSubstituted() {
        nothingConfigured();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.getPrompt(JOB, PromptType.INTERVIEW, PromptStage.START));

        // The old behaviour returned a prompt here. Returning anything at all
        // is the regression this guards.
        assertTrue(refused.getMessage().contains(JOB), "the refusal should name the job");
        assertTrue(refused.getMessage().contains("Manage AI Prompts"),
                "and say where to add the prompt, or the administrator is left guessing");
    }

    @Test
    void theInterviewIsTreatedLikeAptitudeAndCoding() {
        // Those two always required their own prompt. The interview having a
        // hidden default was the inconsistency, not their strictness.
        nothingConfigured();

        for (PromptType type : new PromptType[] {
                PromptType.INTERVIEW, PromptType.APTITUDE, PromptType.CODING }) {
            assertThrows(IllegalStateException.class,
                    () -> service.getPrompt(JOB, type, PromptStage.START),
                    type + " should require a configured prompt");
        }
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
    void aRoundWithNoPromptOfItsOwnUsesTheSharedOne() {
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
    void aMissingStartPromptStillThrowsThroughTheRoundAwarePath() {
        // getInterviewPrompt swallows the absence for SUMMARY — grading falls
        // back to built-in criteria. START has no such allowance: with no
        // prompt there is nothing to interview with.
        nothingConfigured();

        assertThrows(IllegalStateException.class,
                () -> service.getInterviewPrompt(JOB, InterviewRound.L2_TECHNICAL, PromptStage.START));
    }

    @Test
    void aMissingSummaryPromptIsToleratedNotFatal() {
        nothingConfigured();

        assertEquals(null,
                service.getInterviewPrompt(JOB, InterviewRound.L2_TECHNICAL, PromptStage.SUMMARY),
                "grading without a custom summary prompt uses the built-in criteria");
    }
}
