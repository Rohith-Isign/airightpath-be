package com.rightpath.dto;

import java.time.LocalDateTime;

import com.rightpath.entity.ProctoringEvent;

/**
 * One proctoring event, as the reviewer's screen needs it.
 *
 * <p>The endpoint used to return {@link ProctoringEvent} itself, and every call
 * answered 500. The entity points back at its schedule, the schedule holds a
 * list of its events, and neither side is marked {@code @JsonIgnore} — so
 * Jackson walked schedule → events → schedule until the stack ran out. Nothing
 * caught it earlier because the proctoring panel is only reached by opening a
 * finished interview's detail, and the transcript beside it was failing the
 * same way for its own reasons until that was projected too.</p>
 *
 * <p>{@code scheduleId} rather than the schedule: the caller asked for one
 * interview's events and already knows which. Read through {@code getId()} on
 * the proxy, which needs no database round trip and no open session.</p>
 *
 * @param id         this event's id
 * @param scheduleId the interview it belongs to
 * @param eventType  what happened — a tab switch, a lost face, a stopped share
 * @param details    the human-readable specifics recorded with it
 * @param timestamp  when it happened
 */
public record ProctoringEventDTO(
        Long id,
        Long scheduleId,
        String eventType,
        String details,
        LocalDateTime timestamp) {

    public static ProctoringEventDTO from(ProctoringEvent event) {
        return new ProctoringEventDTO(
                event.getId(),
                event.getSchedule() != null ? event.getSchedule().getId() : null,
                event.getEventType(),
                event.getDetails(),
                event.getTimestamp());
    }
}
