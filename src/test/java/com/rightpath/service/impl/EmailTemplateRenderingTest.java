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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import com.rightpath.enums.EmailType;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.service.WhatsAppService;
import com.rightpath.util.BusinessSchedule;

/**
 * Guards the properties that made the old mails read as machine output: the word
 * "null" in the body, an empty greeting, a missing subject, or a call to action
 * with no link behind it.
 *
 * <p>{@link SchedulingEmailFormatTest} covers how a slot is formatted; this class
 * covers every template, including the ones with no slot at all.</p>
 */
class EmailTemplateRenderingTest {

	private static final LocalDateTime SLOT = LocalDateTime.parse("2026-08-12T14:30");

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

	@ParameterizedTest
	@EnumSource(EmailType.class)
	void everyTemplateRendersACompleteBrandedMail(EmailType emailType) {
		Sent sent = send(emailType, fullParams());

		assertFalse(sent.subject().isBlank(), emailType + " must have a subject");
		assertTrue(sent.body().contains("<!DOCTYPE html>"), emailType + " must render the shared layout");
		assertTrue(sent.body().contains("RightPath"), emailType + " must carry the company name");
		assertNoPlaceholderLeak(emailType, sent.body());
	}

	/**
	 * The real failure mode of a parameter map: a caller that doesn't know about a
	 * key. Missing values must vanish from the mail, not surface as "null".
	 */
	@ParameterizedTest
	@EnumSource(EmailType.class)
	void aTemplateGivenOnlyAnAddressStillReadsLikeAnEmail(EmailType emailType) {
		Map<String, Object> params = new HashMap<>();
		params.put("recipientEmail", "candidate@example.com");

		Sent sent = send(emailType, params);

		assertNoPlaceholderLeak(emailType, sent.body());
		assertFalse(sent.subject().contains("null"), emailType + " subject leaked null: " + sent.subject());
	}

	@Test
	void aCandidateIsAddressedByNameAndFallsBackToACourteousGreeting() {
		assertTrue(send(EmailType.APPLICATION_SUCCESS, fullParams()).body().contains("Dear <strong>Asha Rao</strong>"));

		Map<String, Object> nameless = new HashMap<>();
		nameless.put("recipientEmail", "candidate@example.com");
		assertTrue(send(EmailType.APPLICATION_SUCCESS, nameless).body().contains("Dear <strong>Candidate</strong>"));
	}

	@Test
	void theAcknowledgementCarriesItsConfirmationLink() {
		String body = send(EmailType.ACKNOWLEDGEMENT, fullParams()).body();

		assertTrue(body.contains("https://example.test/ack"), "the confirm button must link somewhere: " + body);
		assertTrue(body.contains("Yes, I&#39;ll be there") || body.contains("Yes, I'll be there"),
				"the confirm button must be labelled: " + body);
	}

	/** A button with no URL is worse than no button — it must not be drawn at all. */
	@Test
	void anAcknowledgementWithNoLinkDrawsNoButton() {
		Map<String, Object> params = fullParams();
		params.remove("acknowledgeUrl");

		assertFalse(send(EmailType.ACKNOWLEDGEMENT, params).body().contains("Button not working?"));
	}

	@Test
	void theExamMailCarriesTheTestLinkAndTheRules() {
		String body = send(EmailType.EXAM_SCHEDULE, examParams()).body();

		assertTrue(body.contains("Start my test"), "the exam mail must offer a way in: " + body);
		assertTrue(body.contains("Before you begin"), "the exam mail must set expectations: " + body);
	}

	@Test
	void candidateSuppliedTextCannotBreakOutIntoMarkup() {
		Map<String, Object> params = fullParams();
		params.put("firstName", "<script>alert('x')</script>");

		String body = send(EmailType.APPLICATION_SUCCESS, params).body();

		assertFalse(body.contains("<script>"), "candidate text was rendered as markup: " + body);
		assertTrue(body.contains("&lt;script&gt;"));
	}

	@Test
	void thePlainTextAlternativeKeepsTheWordsAndTheLinks() {
		String text = EmailServiceImpl.toPlainText(send(EmailType.ACKNOWLEDGEMENT, fullParams()).body());

		assertFalse(text.contains("<"), "markup leaked into the text part: " + text);
		assertTrue(text.contains("https://example.test/ack"), "the link must survive: " + text);
		assertTrue(text.contains("Dear Asha Rao,"), "the greeting must survive: " + text);
	}

	private static void assertNoPlaceholderLeak(EmailType emailType, String body) {
		assertFalse(body.contains("null"), emailType + " leaked a null into the body: " + body);
		assertFalse(body.contains("{{"), emailType + " left an unfilled placeholder: " + body);
	}

	private record Sent(String subject, String body) {
	}

	private Sent send(EmailType emailType, Map<String, Object> params) {
		emailService.sendUniversalEmail(emailType, params);

		ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(emailService, org.mockito.Mockito.atLeastOnce())
				.sendHtmlEmail(anyString(), subject.capture(), body.capture());
		return new Sent(subject.getValue(), body.getValue());
	}

	/** Everything any template could ask for, so one map drives them all. */
	private static Map<String, Object> fullParams() {
		Map<String, Object> params = new HashMap<>();
		params.put("recipientEmail", "candidate@example.com");
		params.put("firstName", "Asha");
		params.put("lastName", "Rao");
		params.put("mobileNumber", "9876543210");
		params.put("jobTitle", "Junior Software Developer");
		params.put("jobPrefix", "FE-DEV-2026-005");
		params.put("acknowledgeUrl", "https://example.test/ack");
		params.put("otp", "482913");
		params.put("message", "Your password was updated on 12 Aug 2026.");
		params.put(EmailServiceImpl.EXAM_SCHEDULE_PARAM, SLOT);
		return params;
	}

	private static Map<String, Object> examParams() {
		Map<String, Object> params = fullParams();
		params.put("startTime", SLOT);
		params.put("endTime", SLOT.plusHours(1));
		return params;
	}
}
