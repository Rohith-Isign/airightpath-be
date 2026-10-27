package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/**
 * Pins the candidate-facing schedule format and the past-slot rule that the
 * scheduling endpoints rely on.
 */
class BusinessScheduleTest {

	private final BusinessSchedule schedule = new BusinessSchedule("Asia/Kolkata");

	@Test
	void formatsAfternoonSlotAsCandidateReadableText() {
		assertEquals("Wed, 12 Aug 2026, 02:30 PM IST",
				schedule.format(LocalDateTime.parse("2026-08-12T14:30")));
	}

	@Test
	void formatsMidnightAndNoonOnTwelveHourClock() {
		assertEquals("Wed, 12 Aug 2026, 12:00 AM IST",
				schedule.format(LocalDateTime.parse("2026-08-12T00:00")));
		assertEquals("Wed, 12 Aug 2026, 12:00 PM IST",
				schedule.format(LocalDateTime.parse("2026-08-12T12:00")));
	}

	@Test
	void formatCarriesNoIsoArtefacts() {
		String display = schedule.format(LocalDateTime.parse("2026-08-12T14:30"));

		assertFalse(containsPattern(display, "\\d{4}-\\d{2}-\\d{2}"), "no ISO date: " + display);
		assertFalse(containsPattern(display, "\\d{2}:\\d{2}:\\d{2}"), "no seconds: " + display);
		assertFalse(containsPattern(display, "\\dT\\d"), "no ISO date/time separator: " + display);
		assertFalse(display.contains("14:"), "no 24-hour clock: " + display);
	}

	private static boolean containsPattern(String text, String regex) {
		return java.util.regex.Pattern.compile(regex).matcher(text).find();
	}

	@Test
	void formatsSeparateDateAndTimeColumnsIdentically() {
		assertEquals(schedule.format(LocalDateTime.parse("2026-08-12T14:30")),
				schedule.format(LocalDate.parse("2026-08-12"), LocalTime.parse("14:30")));
	}

	@Test
	void rendersPlaceholderRatherThanNullOrRawValues() {
		assertEquals("To be confirmed", schedule.format((LocalDateTime) null));
		assertEquals("To be confirmed", schedule.format(null, LocalTime.parse("14:30")));
		assertEquals("To be confirmed", schedule.formatForTemplate(null));
		// A template handed the wrong type must not leak it into candidate copy.
		assertEquals("To be confirmed", schedule.formatForTemplate("2026-08-12T14:30"));
	}

	@Test
	void resolvesNowInTheConfiguredZoneNotTheJvmDefault() {
		assertEquals(ZoneId.of("Asia/Kolkata"), schedule.zone());
		assertEquals(LocalDate.now(ZoneId.of("Asia/Kolkata")), schedule.today());
		assertEquals(LocalDate.now(ZoneId.of("Asia/Kolkata")), schedule.now().toLocalDate());
	}

	@Test
	void requiresDateTimeToBePresent() {
		assertEquals("Date & time is required.",
				assertThrows(IllegalArgumentException.class, () -> schedule.requireFutureSlot(null)).getMessage());
		assertEquals("Date & time is required.",
				assertThrows(IllegalArgumentException.class, () -> schedule.requireFutureSlot("   ")).getMessage());
	}

	@Test
	void rejectsPastSlots() {
		String past = schedule.now().minusMinutes(30).toString();

		assertEquals("Date & time cannot be in the past.",
				assertThrows(IllegalArgumentException.class, () -> schedule.requireFutureSlot(past)).getMessage());
	}

	@Test
	void rejectsAPastSlotJustOutsideTheTolerance() {
		String barelyPast = schedule.now().minusMinutes(2).toString();

		assertThrows(IllegalArgumentException.class, () -> schedule.requireFutureSlot(barelyPast));
	}

	@Test
	void toleratesTheSlotChosenAtTheTopOfTheCurrentMinute() {
		// What the UI actually sends: minute precision, submitted seconds later.
		String currentMinute = schedule.now().withSecond(0).withNano(0).toString();

		assertEquals(schedule.now().withSecond(0).withNano(0),
				schedule.requireFutureSlot(currentMinute));
	}

	@Test
	void acceptsFutureSlots() {
		assertEquals(LocalDateTime.parse("2099-01-31T09:05"),
				schedule.requireFutureSlot("2099-01-31T09:05"));
	}

	@Test
	void rejectsUnparseableValues() {
		assertEquals("Invalid dateTime format. Expected: yyyy-MM-ddTHH:mm",
				assertThrows(IllegalArgumentException.class,
						() -> schedule.requireFutureSlot("12/08/2026 2:30 PM")).getMessage());
	}

	@Test
	void optionalSlotAllowsAbsenceButStillRejectsThePast() {
		assertNull(schedule.optionalFutureSlot(null));
		assertNull(schedule.optionalFutureSlot(""));
		assertEquals(LocalDateTime.parse("2099-01-31T09:05"), schedule.optionalFutureSlot("2099-01-31T09:05"));

		String past = schedule.now().minusDays(1).toString();
		assertEquals("Date & time cannot be in the past.",
				assertThrows(IllegalArgumentException.class, () -> schedule.optionalFutureSlot(past)).getMessage());
	}
}
