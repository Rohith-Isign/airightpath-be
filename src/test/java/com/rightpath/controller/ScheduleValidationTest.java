package com.rightpath.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.dto.BulkMailRequestDTO;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.service.JobApplicationForCandidateService;
import com.rightpath.util.BusinessSchedule;

/**
 * A rejected schedule must fail the whole request before the send loop starts —
 * client-side validation is not a control, and a partially-sent bulk action cannot
 * be undone.
 *
 * <p>The thrown {@link IllegalArgumentException} is what
 * {@code GlobalExceptionHandler} turns into a 400 {@code ApiError}.</p>
 */
class ScheduleValidationTest {

	private final BusinessSchedule schedule = new BusinessSchedule("Asia/Kolkata");

	private JobApplicationForCandidateService service;
	private JobApplicationForCandidateController controller;

	@BeforeEach
	void setUp() {
		service = mock(JobApplicationForCandidateService.class);
		controller = new JobApplicationForCandidateController(
				mock(JobApplicationForCandidateRepository.class), service);
		ReflectionTestUtils.setField(controller, "businessSchedule", schedule);
	}

	@Test
	void ackMailWithPastDateTimeMailsNobody() {
		BulkMailRequestDTO request = requestWith(schedule.now().minusHours(3).toString());

		assertEquals("Date & time cannot be in the past.",
				assertThrows(IllegalArgumentException.class, () -> controller.sendAckMail(request)).getMessage());
		verifyNoInteractions(service);
	}

	@Test
	void ackMailWithMissingDateTimeIsRejected() {
		assertEquals("Date & time is required.",
				assertThrows(IllegalArgumentException.class,
						() -> controller.sendAckMail(requestWith(null))).getMessage());

		assertThrows(IllegalArgumentException.class, () -> controller.sendAckMail(requestWith("  ")));
		verifyNoInteractions(service);
	}

	@Test
	void ackMailWithUnparseableDateTimeIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> controller.sendAckMail(requestWith("12 Aug 2026 2:30 PM")));
		verifyNoInteractions(service);
	}

	@Test
	void ackMailRequiresRecipientsAndJobPrefixBeforeTouchingTheService() {
		BulkMailRequestDTO noEmails = requestWith("2099-01-31T09:05");
		noEmails.setEmails(List.of());
		assertThrows(IllegalArgumentException.class, () -> controller.sendAckMail(noEmails));

		BulkMailRequestDTO noPrefix = requestWith("2099-01-31T09:05");
		noPrefix.setJobPrefix(" ");
		assertThrows(IllegalArgumentException.class, () -> controller.sendAckMail(noPrefix));

		verifyNoInteractions(service);
	}

	@Test
	void examLinkWithPastDateTimeMailsNobody() {
		BulkMailRequestDTO request = requestWith(schedule.now().minusDays(1).toString());

		assertEquals("Date & time cannot be in the past.",
				assertThrows(IllegalArgumentException.class, () -> controller.sendExamLink(request)).getMessage());
		verifyNoInteractions(service);
	}

	@Test
	void examLinkStillAllowsAnOmittedDateTime() {
		controller.sendExamLink(requestWith(null));

		// Null slot reaches the service, which starts the exam window now.
		org.mockito.Mockito.verify(service).sendExamLink("FE-DEV-2026-005", "a@x.com", null);
	}

	@Test
	void validFutureSlotIsPassedThroughAsParsed() {
		controller.sendAckMail(requestWith("2099-01-31T09:05"));

		org.mockito.Mockito.verify(service).sendAcknowledgementMailAndUpdateStatus(
				"FE-DEV-2026-005", "a@x.com", LocalDateTime.parse("2099-01-31T09:05"));
	}

	private static BulkMailRequestDTO requestWith(String dateTime) {
		BulkMailRequestDTO request = new BulkMailRequestDTO();
		request.setEmails(List.of("a@x.com"));
		request.setJobPrefix("FE-DEV-2026-005");
		request.setDateTime(dateTime);
		return request;
	}
}
