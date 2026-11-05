package com.rightpath.util;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads a request body into memory only after its declared length has been checked.
 *
 * <p>Binding a {@code byte[]} parameter reads however much the client sends before the controller can
 * look at it, so a few large concurrent uploads exhaust the heap. This refuses an oversized or
 * unsized body first. Large recordings should go straight to storage on a presigned URL; this is the
 * guard on the pieces that still pass through.</p>
 */
public final class BoundedBody {

    private BoundedBody() {
    }

    public static byte[] read(HttpServletRequest request, long maxBytes) throws IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            throw new ResponseStatusException(HttpStatus.LENGTH_REQUIRED, "Content-Length is required");
        }
        if (length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Part is larger than " + maxBytes + " bytes; request a presigned upload URL instead");
        }
        return request.getInputStream().readNBytes((int) length);
    }
}
