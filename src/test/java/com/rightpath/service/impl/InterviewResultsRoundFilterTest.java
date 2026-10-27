package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.enums.InterviewRound;
import com.rightpath.repository.CandidateInterviewScheduleRepository;

/**
 * Narrowing the results list to one interview round.
 *
 * <p>The round filter feeds two endpoints that must agree — the results table
 * and the summary cards above it both call this method — so the filtering lives
 * here rather than in either controller.</p>
 *
 * <p>The case worth protecting is the historic one. Interviews booked before the
 * round column existed have a null round, and MySQL comparisons against null
 * are never true: filtering on the stored value would drop those rows from
 * <em>both</em> rounds, so a reviewer picking either filter would see fewer
 * interviews than "all rounds" showed, with nothing to explain the gap.</p>
 */
class InterviewResultsRoundFilterTest {

    private static final String JOB = "RP-AIML-01";

    private CandidateInterviewScheduleRepository scheduleRepo;
    private InterviewServiceImpl service;

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(CandidateInterviewScheduleRepository.class);
        // Only the schedule repository is exercised by getResults; the rest of
        // the interview machinery is irrelevant to a read of stored rows.
        service = new InterviewServiceImpl(null, scheduleRepo, null, null, null, null, null);
    }

    private CandidateInterviewSchedule schedule(String email, InterviewRound round) {
        CandidateInterviewSchedule schedule = new CandidateInterviewSchedule();
        schedule.setEmail(email);
        schedule.setJobPrefix(JOB);
        schedule.setRound(round);
        return schedule;
    }

    private void givenSchedules(CandidateInterviewSchedule... schedules) {
        when(scheduleRepo.findAllByJobPrefix(JOB)).thenReturn(List.of(schedules));
    }

    @Test
    void noRoundReturnsEveryRound() {
        givenSchedules(
                schedule("tech@example.test", InterviewRound.L2_TECHNICAL),
                schedule("behave@example.test", InterviewRound.L3_BEHAVIORAL),
                schedule("historic@example.test", null));

        assertEquals(3, service.getResults(JOB, null).size());
    }

    @Test
    void aRoundKeepsOnlyThatRound() {
        givenSchedules(
                schedule("tech@example.test", InterviewRound.L2_TECHNICAL),
                schedule("behave@example.test", InterviewRound.L3_BEHAVIORAL));

        List<CandidateInterviewSchedule> behavioural =
                service.getResults(JOB, InterviewRound.L3_BEHAVIORAL);

        assertEquals(1, behavioural.size());
        assertEquals("behave@example.test", behavioural.get(0).getEmail());
    }

    @Test
    void anInterviewWithNoStoredRoundCountsAsTechnical() {
        // The whole reason this filters on getEffectiveRound(). A pre-rounds
        // interview is a technical one — that was the only interview there was.
        givenSchedules(schedule("historic@example.test", null));

        assertEquals(1, service.getResults(JOB, InterviewRound.L2_TECHNICAL).size(),
                "a null round should read as the default round, not as no round");
        assertTrue(service.getResults(JOB, InterviewRound.L3_BEHAVIORAL).isEmpty(),
                "a null round must not also answer to the behavioural filter");
    }

    @Test
    void theRoundsPartitionTheList() {
        // No row is dropped by both filters and none is counted by both, so the
        // two filtered views always add up to the unfiltered one. This is what
        // stops the summary cards from disagreeing with the table.
        givenSchedules(
                schedule("a@example.test", InterviewRound.L2_TECHNICAL),
                schedule("b@example.test", InterviewRound.L3_BEHAVIORAL),
                schedule("c@example.test", null),
                schedule("d@example.test", InterviewRound.L3_BEHAVIORAL));

        int all = service.getResults(JOB, null).size();
        int technical = service.getResults(JOB, InterviewRound.L2_TECHNICAL).size();
        int behavioural = service.getResults(JOB, InterviewRound.L3_BEHAVIORAL).size();

        assertEquals(all, technical + behavioural);
        assertEquals(2, technical);
        assertEquals(2, behavioural);
    }

    @Test
    void filteringWithoutAJobStillWorks() {
        // The endpoints allow an absent jobPrefix, which reads every interview.
        when(scheduleRepo.findAll()).thenReturn(List.of(
                schedule("tech@example.test", InterviewRound.L2_TECHNICAL),
                schedule("behave@example.test", InterviewRound.L3_BEHAVIORAL)));

        assertEquals(1, service.getResults(null, InterviewRound.L2_TECHNICAL).size());
        assertEquals(1, service.getResults("  ", InterviewRound.L3_BEHAVIORAL).size());
    }
}
