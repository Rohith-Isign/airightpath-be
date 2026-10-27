package com.rightpath.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.ProctoringEvent;

/**
 * The proctoring panel's endpoint must be serialisable.
 *
 * <p>It was not. The endpoint returned {@link ProctoringEvent} entities, the
 * event points back at its schedule, the schedule holds a list of its events,
 * and neither side is ignored by Jackson — so every call to
 * {@code /api/interview/{id}/proctoring-events} walked the cycle and answered
 * 500. The panel was empty for every interview, and the generic error body gave
 * no hint why.</p>
 *
 * <p>The first test here fails against the old code and passes against the
 * projection, which is the point of it: the next person tempted to return the
 * entity directly finds out in the build rather than from a reviewer.</p>
 */
class ProctoringEventSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    /** An event wired to its schedule in both directions, as JPA hands it back. */
    private ProctoringEvent eventInACycle() {
        CandidateInterviewSchedule schedule = new CandidateInterviewSchedule();
        schedule.setId(7L);
        schedule.setJobPrefix("FE-DEV-2026-005-1293");
        schedule.setEmail("candidate@example.test");

        ProctoringEvent event = ProctoringEvent.builder()
                .id(42L)
                .schedule(schedule)
                .eventType("tab_switch")
                .details("Candidate left the interview tab")
                .timestamp(LocalDateTime.now())
                .build();

        // The reverse side, which is what turns a reference into a loop.
        schedule.setProctoringEvents(List.of(event));
        return event;
    }

    @Test
    @DisplayName("an event whose schedule points back at it still serialises")
    void theCycleDoesNotBlowUp() {
        ProctoringEventDTO dto = ProctoringEventDTO.from(eventInACycle());

        assertThatCode(() -> objectMapper.writeValueAsString(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the schedule is flattened to its id, which is all the caller needs")
    void carriesScheduleIdNotTheSchedule() throws Exception {
        String json = objectMapper.writeValueAsString(ProctoringEventDTO.from(eventInACycle()));

        assertThat(json).contains("\"scheduleId\":7");
        // The caller asked for one interview's events and already knows which.
        // Embedding the schedule is both the cycle and a payload nobody reads.
        assertThat(json).doesNotContain("jobPrefix");
        assertThat(json).doesNotContain("proctoringEvents");
    }

    @Test
    @DisplayName("carries the fields the reviewer's panel actually renders")
    void carriesWhatTheScreenNeeds() {
        ProctoringEventDTO dto = ProctoringEventDTO.from(eventInACycle());

        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.eventType()).isEqualTo("tab_switch");
        assertThat(dto.details()).isEqualTo("Candidate left the interview tab");
        assertThat(dto.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("an event with no schedule attached does not fail the whole list")
    void toleratesAMissingSchedule() {
        ProctoringEvent orphan = ProctoringEvent.builder().id(1L).eventType("devtools").build();

        ProctoringEventDTO dto = ProctoringEventDTO.from(orphan);

        assertThat(dto.scheduleId()).isNull();
        assertThatCode(() -> objectMapper.writeValueAsString(dto)).doesNotThrowAnyException();
    }
}
