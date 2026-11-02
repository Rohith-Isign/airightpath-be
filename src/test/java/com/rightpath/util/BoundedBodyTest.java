package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class BoundedBodyTest {

    @Test
    void aBodyWithinTheLimitIsReadWhole() throws Exception {
        byte[] body = { 1, 2, 3, 4 };
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        assertArrayEquals(body, BoundedBody.read(request, 10));
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsRead() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(new byte[11]);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> BoundedBody.read(request, 10));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatusCode());
    }

    @Test
    void aBodyWithNoDeclaredLengthIsRefused() {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> BoundedBody.read(request, 10));
        assertEquals(HttpStatus.LENGTH_REQUIRED, e.getStatusCode());
    }
}
