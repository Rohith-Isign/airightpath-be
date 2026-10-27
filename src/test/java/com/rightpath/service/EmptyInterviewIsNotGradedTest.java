package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.enums.InterviewResult;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.VoiceConversationEntryRepository;
import com.rightpath.util.EvaluationCategoryFormatter;
import com.rightpath.util.PromptPlaceholderResolver;

/**
 * An interview with no answers must not be given a score.
 *
 * <p>This is not a hypothetical. A real interview timed out before the
 * candidate said anything, leaving a transcript of one line — the interviewer's
 * own greeting. Asked to evaluate it, the model did not decline: it returned
 * 7.0, {@code LEAN_HIRE}, "the candidate demonstrated a solid understanding of
 * fundamental data structures", and Technical Skills 8.0. The schedule was then
 * marked {@code PASSED}, which is the field a hiring decision reads.</p>
 *
 * <p>The failure mode is what makes it dangerous: a language model asked to
 * grade an empty transcript produces confident, well-formed, entirely invented
 * output. Nothing downstream can tell that apart from a real assessment, so the
 * check has to happen before the model is asked.</p>
 */
class EmptyInterviewIsNotGradedTest {

    private static final long SCHEDULE_ID = 4242L;

    private CandidateInterviewScheduleRepository scheduleRepo;
    private VoiceConversationEntryRepository entryRepository;
    private OpenAiStreamingService openAi;
    private InterviewEvaluationService service;

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(CandidateInterviewScheduleRepository.class);
        entryRepository = mock(VoiceConversationEntryRepository.class);
        openAi = mock(OpenAiStreamingService.class);

        service = new InterviewEvaluationService(
                openAi,
                scheduleRepo,
                entryRepository,
                mock(JobPromptService.class),
                mock(EvaluationCategoryFormatter.class),
                mock(com.rightpath.repository.JobPromptRepository.class),
                mock(PromptPlaceholderResolver.class),
                new com.rightpath.util.PromptInjectionGuard(),
                new InterviewReviewTriage());
    }

    private CandidateInterviewSchedule schedule() {
        CandidateInterviewSchedule schedule = new CandidateInterviewSchedule();
        schedule.setId(SCHEDULE_ID);
        schedule.setJobPrefix("RP-AIML-01");
        schedule.setEmail("candidate@example.test");
        schedule.setInterviewerName("Sarah");
        schedule.setInterviewResult(InterviewResult.PENDING);
        when(scheduleRepo.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));
        return schedule;
    }

    private VoiceConversationEntry entry(ConversationRole role, String content) {
        VoiceConversationEntry e = new VoiceConversationEntry();
        e.setRole(role);
        e.setContent(content);
        return e;
    }

    private void transcript(VoiceConversationEntry... entries) {
        when(entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(SCHEDULE_ID))
                .thenReturn(List.of(entries));
    }

    @Test
    void anInterviewWithOnlyAGreetingIsNotSentToTheModel() {
        // Exactly the transcript that produced the fabricated 7.0/LEAN_HIRE.
        CandidateInterviewSchedule schedule = schedule();
        transcript(entry(ConversationRole.INTERVIEWER, "Hello, and welcome. I'm Sarah..."));

        VoiceEvaluationResult result = service.evaluateInterview(SCHEDULE_ID);

        verify(openAi, never()).chatCompletion(anyList());
        assertEquals(0, result.getOverallScore(), "an unanswered interview has no score");
        assertTrue(result.getCategoryScores().isEmpty(), "and no per-area scores to show");
        assertTrue(result.getSummary().toLowerCase().contains("not assessable"),
                "the reviewer needs to be told why the panel is empty");
    }

    @Test
    void theResultIsLeftForAPersonToDecide() {
        // Neither PASSED nor FAILED. "Did not answer" could be a no-show, a
        // broken microphone or a withdrawal, and those want different
        // decisions — none of which this service can make.
        CandidateInterviewSchedule schedule = schedule();
        transcript(entry(ConversationRole.INTERVIEWER, "Hello..."));

        VoiceEvaluationResult result = service.evaluateInterview(SCHEDULE_ID);

        assertEquals(InterviewResult.PENDING, schedule.getInterviewResult(),
                "an interview with no answers must not come out as PASSED");
        assertNull(result.getRecommendation(),
                "a recommendation would be a judgement made on no evidence");
    }

    @Test
    void skippedQuestionsDoNotCountAsAnswers() {
        // A candidate who skipped everything said as little as one who said
        // nothing, and the transcript is just as empty of evidence.
        CandidateInterviewSchedule schedule = schedule();
        transcript(
                entry(ConversationRole.INTERVIEWER, "First question?"),
                entry(ConversationRole.CANDIDATE, "[NO RESPONSE - SKIPPED]"),
                entry(ConversationRole.INTERVIEWER, "Second question?"),
                entry(ConversationRole.CANDIDATE, "   "));

        service.evaluateInterview(SCHEDULE_ID);

        verify(openAi, never()).chatCompletion(anyList());
        assertEquals(InterviewResult.PENDING, schedule.getInterviewResult());
    }

    @Test
    void theNotAssessableVerdictIsStoredSoItIsNotRecomputed() {
        CandidateInterviewSchedule schedule = schedule();
        transcript(entry(ConversationRole.INTERVIEWER, "Hello..."));

        service.evaluateInterview(SCHEDULE_ID);

        verify(scheduleRepo).save(any(CandidateInterviewSchedule.class));
        assertTrue(schedule.getEvaluationJson() != null && !schedule.getEvaluationJson().isBlank(),
                "the reviewer should see the same explanation on every visit");
    }
}
