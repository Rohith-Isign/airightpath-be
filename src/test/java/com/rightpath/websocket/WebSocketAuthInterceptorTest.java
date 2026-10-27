package com.rightpath.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.rightpath.entity.InterviewMobilePairing;
import com.rightpath.service.MobilePairingService;
import com.rightpath.service.impl.AccessTokenService;

class WebSocketAuthInterceptorTest {

	private final AccessTokenService tokens = mock(AccessTokenService.class);
	private final MobilePairingService pairings = mock(MobilePairingService.class);
	private final WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor(tokens, pairings,
			new com.rightpath.config.AuthProperties());

	private boolean handshake(String query, Map<String, Object> attributes, MockHttpServletResponse out) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/info");
		request.setQueryString(query);
		return interceptor.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(out),
				null, attributes);
	}

	@Test
	void aPhoneWithAnOpenPairingIsLetInButNotAuthenticated() {
		when(pairings.resolveOrNull("tok-1")).thenReturn(new InterviewMobilePairing());
		Map<String, Object> attributes = new HashMap<>();

		assertTrue(handshake("mobileToken=tok-1", attributes, new MockHttpServletResponse()));
		assertEquals("tok-1", attributes.get(WebSocketAuthInterceptor.MOBILE_TOKEN_ATTR));
		assertEquals(false, attributes.get(WebSocketAuthInterceptor.AUTHENTICATED_ATTR));
		assertFalse(attributes.containsKey(WebSocketAuthInterceptor.USER_EMAIL_ATTR));
	}

	@Test
	void aPhoneWithAClosedOrUnknownPairingIsRefused() {
		when(pairings.resolveOrNull("tok-2")).thenReturn(null);
		MockHttpServletResponse out = new MockHttpServletResponse();

		assertFalse(handshake("mobileToken=tok-2", new HashMap<>(), out));
		assertEquals(HttpStatus.UNAUTHORIZED.value(), out.getStatus());
	}

	@Test
	void nothingAtAllIsRefused() {
		MockHttpServletResponse out = new MockHttpServletResponse();

		assertFalse(handshake(null, new HashMap<>(), out));
		assertEquals(HttpStatus.UNAUTHORIZED.value(), out.getStatus());
	}
}
