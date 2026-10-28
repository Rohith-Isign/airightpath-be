package com.rightpath.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.rightpath.service.impl.AccessTokenService;

class WebSocketAuthInterceptorTest {

	private final AccessTokenService tokens = mock(AccessTokenService.class);
	private final WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor(tokens,
			new com.rightpath.config.AuthProperties());

	private boolean handshake(String query, Map<String, Object> attributes, MockHttpServletResponse out) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/info");
		request.setQueryString(query);
		return interceptor.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(out),
				null, attributes);
	}

	@Test
	void aPhoneWithOnlyAPairingTokenIsRefused() {
		MockHttpServletResponse out = new MockHttpServletResponse();

		assertFalse(handshake("mobileToken=tok-1", new HashMap<>(), out));
		assertEquals(HttpStatus.UNAUTHORIZED.value(), out.getStatus());
	}

	@Test
	void nothingAtAllIsRefused() {
		MockHttpServletResponse out = new MockHttpServletResponse();

		assertFalse(handshake(null, new HashMap<>(), out));
		assertEquals(HttpStatus.UNAUTHORIZED.value(), out.getStatus());
	}
}
