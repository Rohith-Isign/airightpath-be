package com.rightpath.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.service.impl.MobileConnectionServiceImpl;

/**
 * Pairing a phone must not depend on which side registered first.
 *
 * <p>The phone keeps its camera closed until it is told the desktop is there.
 * That signal was only sent when the <em>desktop</em> registered, and in the
 * order this actually happens — the interview page is already open, the
 * candidate then picks up their phone and scans the code — the desktop had
 * registered and gone quiet long before the phone was listening. The phone
 * waited forever, while the desktop was told "mobile ready", displayed "Phone
 * connected" and showed an empty preview for the whole interview.</p>
 *
 * <p>It appeared to work intermittently, which is the worst version of this:
 * an unrelated reconnect on the desktop re-registered it and rescued the
 * handshake, so the same build paired on one run and not the next.</p>
 */
class MobilePairingHandshakeTest {

	private static final String TOKEN = "pair-token";
	private static final String DESKTOP_SESSION = "desktop-session";
	private static final String MOBILE_SESSION = "mobile-session";
	private static final String READY = "/queue/mobile/ready";

	private SimpMessagingTemplate messaging;
	private MobileWebSocketController controller;

	@BeforeEach
	void setUp() {
		messaging = mock(SimpMessagingTemplate.class);
		controller = new MobileWebSocketController();
		ReflectionTestUtils.setField(controller, "messagingTemplate", messaging);
		ReflectionTestUtils.setField(controller, "mobileConnectionService", new MobileConnectionServiceImpl());
	}

	private static SimpMessageHeaderAccessor session(String sessionId) {
		SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
		accessor.setSessionId(sessionId);
		return accessor;
	}

	private void registerDesktop() {
		controller.registerDesktop(Map.of("token", TOKEN), session(DESKTOP_SESSION));
	}

	private void registerMobile() {
		controller.registerMobile(Map.of("token", TOKEN), session(MOBILE_SESSION));
	}

	/** The real order: page open, then the candidate scans the code. */
	@Test
	void aPhoneThatRegistersSecondIsStillToldToStart() {
		registerDesktop();
		registerMobile();

		verify(messaging).convertAndSendToUser(eq(MOBILE_SESSION), eq(READY), any());
		verify(messaging).convertAndSendToUser(eq(DESKTOP_SESSION), eq(READY), any());
	}

	/** The reverse order has to work too — the phone may reconnect first. */
	@Test
	void aDesktopThatRegistersSecondStillStartsThePhone() {
		registerMobile();
		registerDesktop();

		verify(messaging).convertAndSendToUser(eq(MOBILE_SESSION), eq(READY), any());
	}

	/**
	 * Registering alone must stay silent. Telling a phone to open its camera
	 * for a desktop that is not there would leave it streaming into nothing.
	 */
	@Test
	void registeringAloneSignalsNobody() {
		registerMobile();

		verify(messaging, never()).convertAndSendToUser(any(String.class), any(String.class), any());
	}

	/**
	 * The interview page's retry. It has always been sent and was always
	 * dropped — no handler existed for the destination, so Spring discarded it
	 * and the client had no way to know.
	 */
	@Test
	void theDesktopsRetryReachesThePhone() {
		registerDesktop();
		registerMobile();

		controller.handleReady(TOKEN, null);

		// Twice: once from the registration above, once from this retry.
		verify(messaging, org.mockito.Mockito.times(2))
				.convertAndSendToUser(eq(MOBILE_SESSION), eq(READY), any());
	}

	/** A retry for a phone that never paired goes nowhere rather than failing. */
	@Test
	void aRetryWithNoPhonePairedIsIgnored() {
		registerDesktop();

		controller.handleReady(TOKEN, null);

		verify(messaging, never()).convertAndSendToUser(any(String.class), any(String.class), any());
	}
}
