package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import com.rightpath.enums.EmailType;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.service.WhatsAppService;
import com.rightpath.util.BusinessSchedule;

/**
 * Renders each scheduling email and asserts the schedule reads as a candidate
 * expects — the defect QA raised was the raw ISO value reaching the body.
 *
 * <p>Only {@code sendHtmlEmail} is stubbed, so the assertions run against the real
 * template output.</p>
 */
class SchedulingEmailFormatTest {

	private static final LocalDateTime SLOT = LocalDateTime.parse("2026-08-12T14:30");
	private static final String EXPECTED_DISPLAY = "Wed, 12 Aug 2026, 02:30 PM IST";

	private EmailServiceImpl emailService;

	@BeforeEach
	void setUp() {
		EmailServiceImpl real = new EmailServiceImpl(
				mock(JavaMailSender.class),
				mock(WhatsAppService.class),
				mock(UsersRepository.class),
				mock(JobApplicationForCandidateRepository.class),
				new BusinessSchedule("Asia/Kolkata"));
		emailService = spy(real);
		doNothing().when(emailService).sendHtmlEmail(anyString(), any(), any());
	}

	@Test
	void acknowledgementEmailShowsFormattedSchedule() {
		assertScheduleRendered(EmailType.ACKNOWLEDGEMENT, baseParams());
	}

	@Test
	void acknowledgementConfirmationEmailShowsFormattedSchedule() {
		assertScheduleRendered(EmailType.ACKNOWLEDGEMENT_CONFIRMATION, baseParams());
	}

	@Test
	void reconfirmationEmailShowsFormattedSchedule() {
		assertScheduleRendered(EmailType.RECONFIRMATION, baseParams());
	}

	@Test
	void examLinkEmailShowsFormattedStartAndEnd() {
		Map<String, Object> params = baseParams();
		params.put("startTime", SLOT);
		params.put("endTime", SLOT.plusHours(1));

		String body = render(EmailType.EXAM_SCHEDULE, params);

		assertTrue(body.contains(EXPECTED_DISPLAY), "start must be formatted: " + body);
		assertTrue(body.contains("Wed, 12 Aug 2026, 03:30 PM IST"), "end must be formatted: " + body);
		assertNoIsoValue(body);
	}

	@Test
	void midnightAndNoonUseTwelveHourClockInTheEmail() {
		Map<String, Object> midnight = baseParams();
		midnight.put(EmailServiceImpl.EXAM_SCHEDULE_PARAM, LocalDateTime.parse("2026-08-12T00:00"));
		assertTrue(render(EmailType.ACKNOWLEDGEMENT, midnight).contains("Wed, 12 Aug 2026, 12:00 AM IST"));

		Map<String, Object> noon = baseParams();
		noon.put(EmailServiceImpl.EXAM_SCHEDULE_PARAM, LocalDateTime.parse("2026-08-12T12:00"));
		assertTrue(render(EmailType.ACKNOWLEDGEMENT, noon).contains("Wed, 12 Aug 2026, 12:00 PM IST"));
	}

	@Test
	void allSchedulingEmailsRenderTheSameText() {
		String acknowledgement = render(EmailType.ACKNOWLEDGEMENT, baseParams());
		String reconfirmation = render(EmailType.RECONFIRMATION, baseParams());
		String confirmation = render(EmailType.ACKNOWLEDGEMENT_CONFIRMATION, baseParams());

		for (String body : new String[] { acknowledgement, reconfirmation, confirmation }) {
			assertTrue(body.contains("<strong>Date &amp; Time:</strong> " + EXPECTED_DISPLAY),
					"expected identical schedule line, got: " + body);
		}
	}

	private void assertScheduleRendered(EmailType emailType, Map<String, Object> params) {
		String body = render(emailType, params);

		assertTrue(body.contains(EXPECTED_DISPLAY), emailType + " must show '" + EXPECTED_DISPLAY + "': " + body);
		assertNoIsoValue(body);
	}

	private static void assertNoIsoValue(String body) {
		assertFalse(body.contains("2026-08-12T14:30"), "raw ISO value leaked into the email body");
		assertFalse(body.contains("14:30"), "24-hour time leaked into the email body");
		assertFalse(body.contains("2026-08-12"), "ISO date leaked into the email body");
	}

	/** Renders one email and returns the HTML body handed to the transport. */
	private String render(EmailType emailType, Map<String, Object> params) {
		emailService.sendUniversalEmail(emailType, params);

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(emailService, org.mockito.Mockito.atLeastOnce())
				.sendHtmlEmail(anyString(), any(), body.capture());
		return body.getValue();
	}

	private static Map<String, Object> baseParams() {
		Map<String, Object> params = new HashMap<>();
		params.put("recipientEmail", "candidate@example.com");
		params.put("firstName", "Asha");
		params.put("lastName", "Rao");
		params.put("jobTitle", "Junior Software Developer");
		params.put("jobPrefix", "FE-DEV-2026-005");
		params.put("acknowledgeUrl", "https://example.test/ack");
		params.put(EmailServiceImpl.EXAM_SCHEDULE_PARAM, SLOT);
		return params;
	}
}
