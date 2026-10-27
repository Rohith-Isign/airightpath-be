package com.rightpath.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single definition of "what time is it for the business", and the only place
 * a scheduled slot is parsed, validated or rendered for a human.
 *
 * <p>Slots arrive from the admin UI as a local {@code yyyy-MM-ddTHH:mm} string
 * with no offset (e.g. {@code 2026-08-12T14:30}). That wall-clock reading is
 * meaningful only against a zone, and the zone it was chosen in is the business
 * one — so both the "is this in the past?" check and the rendered text resolve it
 * with {@link #zone()} rather than the JVM default. On a UTC server, using
 * {@code LocalDateTime.now()} instead would shift every comparison and every
 * displayed time by the zone offset.</p>
 *
 * <p>Rendering is fixed to {@link Locale#ENGLISH} so month and weekday names do
 * not change with the server's locale, and the zone label comes from the zone
 * itself ({@code IST} for {@code Asia/Kolkata}) so it cannot contradict the
 * configured value.</p>
 */
@Component
public class BusinessSchedule {

    /**
     * Candidate-facing format: {@code Wed, 12 Aug 2026, 02:30 PM IST}.
     *
     * <p>{@code hh} is the 1–12 clock hour, so midnight and noon read
     * {@code 12:00 AM} and {@code 12:00 PM} rather than {@code 00:00}.</p>
     */
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy, hh:mm a zzz", Locale.ENGLISH);

    /** Wire format posted by the admin UI: local date-time, minute precision, no zone. */
    private static final String WIRE_FORMAT_HINT = "yyyy-MM-ddTHH:mm";

    /**
     * How far into the past a slot may still be accepted. Absorbs the seconds
     * between the admin picking a minute-precision slot and the request landing,
     * so a slot chosen at the top of its own minute is not rejected by latency.
     */
    private static final long PAST_TOLERANCE_MINUTES = 1;

    /** Shown when a template has no slot to render, so candidate copy never reads "null". */
    private static final String UNKNOWN_SCHEDULE = "To be confirmed";

    static final String MISSING_MESSAGE = "Date & time is required.";
    static final String PAST_MESSAGE = "Date & time cannot be in the past.";
    static final String INVALID_MESSAGE = "Invalid dateTime format. Expected: " + WIRE_FORMAT_HINT;

    private final ZoneId zone;

    public BusinessSchedule(@Value("${app.business-timezone:Asia/Kolkata}") String businessTimezone) {
        this.zone = ZoneId.of(businessTimezone);
    }

    /** The timezone every date-only and wall-clock decision is evaluated in. */
    public ZoneId zone() {
        return zone;
    }

    /** Current wall-clock date-time in the business timezone. */
    public LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    /** Current date in the business timezone. */
    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /**
     * Renders a slot as the candidate should read it:
     * {@code Wed, 12 Aug 2026, 02:30 PM IST}.
     *
     * @param slot the scheduled wall-clock time, or {@code null} if unknown
     * @return the formatted text, or "To be confirmed" when {@code slot} is null
     */
    public String format(LocalDateTime slot) {
        if (slot == null) {
            return UNKNOWN_SCHEDULE;
        }
        // atZone attaches the business zone without shifting the reading — the value
        // already *is* business wall-clock time.
        return slot.atZone(zone).format(DISPLAY_FORMAT);
    }

    /**
     * Renders a slot held as separate date and time columns.
     *
     * @param date scheduled date, or {@code null} if unknown
     * @param time scheduled time, or {@code null} if unknown
     * @return the formatted text, or "To be confirmed" when either part is null
     */
    public String format(LocalDate date, LocalTime time) {
        return (date == null || time == null) ? UNKNOWN_SCHEDULE : format(LocalDateTime.of(date, time));
    }

    /**
     * Renders whatever a template was handed, without ever leaking a raw ISO
     * string into candidate-facing copy.
     *
     * @param slot expected to be a {@link LocalDateTime}
     * @return the formatted text, or "To be confirmed" for null or any other type
     */
    public String formatForTemplate(Object slot) {
        return (slot instanceof LocalDateTime dateTime) ? format(dateTime) : UNKNOWN_SCHEDULE;
    }

    /**
     * Parses a required slot and rejects anything already past.
     *
     * @param rawDateTime the {@code dateTime} field from the request
     * @return the parsed slot, guaranteed not to be in the past
     * @throws IllegalArgumentException if it is missing, unparseable or past —
     *                                  surfaced as HTTP 400 by the global handler
     */
    public LocalDateTime requireFutureSlot(String rawDateTime) {
        if (rawDateTime == null || rawDateTime.isBlank()) {
            throw new IllegalArgumentException(MISSING_MESSAGE);
        }
        return parseFutureSlot(rawDateTime);
    }

    /**
     * Parses an optional slot, rejecting a past value but allowing its absence.
     *
     * @param rawDateTime the {@code dateTime} field from the request, may be blank
     * @return the parsed slot, or {@code null} when none was supplied
     * @throws IllegalArgumentException if a supplied value is unparseable or past
     */
    public LocalDateTime optionalFutureSlot(String rawDateTime) {
        return (rawDateTime == null || rawDateTime.isBlank()) ? null : parseFutureSlot(rawDateTime);
    }

    private LocalDateTime parseFutureSlot(String rawDateTime) {
        LocalDateTime slot;
        try {
            slot = LocalDateTime.parse(rawDateTime.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        if (slot.isBefore(now().minusMinutes(PAST_TOLERANCE_MINUTES))) {
            throw new IllegalArgumentException(PAST_MESSAGE);
        }
        return slot;
    }
}
