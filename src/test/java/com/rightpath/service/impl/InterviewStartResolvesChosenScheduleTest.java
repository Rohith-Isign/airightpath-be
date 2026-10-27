package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.enums.InterviewRound;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.CandidateInterviewScheduleRepository;

/**
 * Starting the interview the candidate actually chose.
 *
 * <p>A candidate can have more than one interview outstanding at once — a
 * repeat L2 alongside an L3 — and they pick from a list before reaching the
 * interview screen. The start call carried only their email and the job, so the
 * server fell back to whichever schedule had been assigned most recently: the
 * choice was discarded, and an administrator booking an L3 after a repeat L2
 * decided which round the candidate would sit.</p>
 *
 * <p>The ownership check is the other half. The id comes from the browser and
 * schedule ids are sequential, so without it editing one number would start
 * another candidate's interview and write answers into their transcript.</p>
 */
class InterviewStartResolvesChosenScheduleTest {

    private static final String JOB = "RP-AIML-01";
    private static final String CANDIDATE = "candidate@example.test";

    private CandidateInterviewScheduleRepository scheduleRepo;
    private VoiceInterviewServiceImpl service;

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(CandidateInterviewScheduleRepository.class);
        // scheduleRepo is first; the rest of the interview machinery plays no
        // part in resolving which schedule to start.
        service = new VoiceInterviewServiceImpl(
                scheduleRepo, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private CandidateInterviewSchedule schedule(long id, String email, InterviewRound round) {
        CandidateInterviewSchedule s = new CandidateInterviewSchedule();
        s.setId(id);
        s.setEmail(email);
        s.setJobPrefix(JOB);
        s.setRound(round);
        return s;
    }

    /** resolveSchedule is private; this is the behaviour under test, not its name. */
    private CandidateInterviewSchedule resolve(String email, Long scheduleId) {
        return (CandidateInterviewSchedule) ReflectionTestUtils.invokeMethod(
                service, "resolveSchedule", JOB, email, scheduleId);
    }

    @Test
    void theChosenScheduleIsUsed() {
        CandidateInterviewSchedule chosen = schedule(7L, CANDIDATE, InterviewRound.L3_BEHAVIORAL);
        when(scheduleRepo.findById(7L)).thenReturn(Optional.of(chosen));

        assertEquals(7L, resolve(CANDIDATE, 7L).getId());
        // The fallback must not run when a choice was made — that is the bug.
        verify(scheduleRepo, never()).findFirstByJobPrefixAndEmailOrderByAssignedAtDesc(anyString(), anyString());
    }

    @Test
    void theChosenRoundSurvivesALaterBooking() {
        // The candidate picked their repeat L2; an L3 booked afterwards would
        // have won under the old most-recent rule.
        CandidateInterviewSchedule repeatL2 = schedule(4L, CANDIDATE, InterviewRound.L2_TECHNICAL);
        when(scheduleRepo.findById(4L)).thenReturn(Optional.of(repeatL2));

        assertEquals(InterviewRound.L2_TECHNICAL, resolve(CANDIDATE, 4L).getEffectiveRound());
    }

    @Test
    void anotherCandidatesInterviewCannotBeStarted() {
        when(scheduleRepo.findById(9L))
                .thenReturn(Optional.of(schedule(9L, "someone.else@example.test", InterviewRound.L2_TECHNICAL)));

        assertThrows(ResourceNotFoundException.class, () -> resolve(CANDIDATE, 9L),
                "a schedule belonging to another candidate must not resolve");
    }

    @Test
    void theOwnershipCheckIgnoresAddressCase() {
        // An address differing only in case is the same account; refusing it
        // would block a legitimate candidate for no gain.
        when(scheduleRepo.findById(3L))
                .thenReturn(Optional.of(schedule(3L, CANDIDATE.toUpperCase(), InterviewRound.L2_TECHNICAL)));

        assertEquals(3L, resolve(CANDIDATE, 3L).getId());
    }

    @Test
    void withNoChoiceTheMostRecentIsStillUsed() {
        // Kept so callers that predate the id — and any other entry point —
        // behave exactly as they did.
        CandidateInterviewSchedule latest = schedule(11L, CANDIDATE, InterviewRound.L2_TECHNICAL);
        when(scheduleRepo.findFirstByJobPrefixAndEmailOrderByAssignedAtDesc(JOB, CANDIDATE))
                .thenReturn(Optional.of(latest));

        assertEquals(11L, resolve(CANDIDATE, null).getId());
        verify(scheduleRepo, never()).findById(any());
    }

    @Test
    void anUnknownScheduleIsRefused() {
        when(scheduleRepo.findById(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> resolve(CANDIDATE, 404L));
    }
}
