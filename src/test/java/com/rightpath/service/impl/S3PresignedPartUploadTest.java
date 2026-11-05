package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Presigning is computed locally, so the URL's shape can be checked without reaching S3. What matters to
 * the browser is that the piece goes to the right object, part and upload, and that the size it declared
 * is part of what was signed.
 */
class S3PresignedPartUploadTest {

    private final S3StorageService storage =
            new S3StorageService("test-bucket", "ap-south-1", "test-access-key", "test-secret-key");

    @Test
    void theUrlNamesTheObjectPartAndUploadAndIsSigned() {
        String url = storage.presignUploadPart("interview", "interview-7/rec.webm", "upload-abc", 3, 5_242_880L,
                Duration.ofMinutes(15));
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);

        assertTrue(url.startsWith("https://test-bucket.s3.ap-south-1.amazonaws.com/interview/interview-7/rec.webm"), url);
        assertTrue(decoded.contains("partNumber=3"), url);
        assertTrue(decoded.contains("uploadId=upload-abc"), url);
        assertTrue(url.contains("X-Amz-Signature="), url);
        assertTrue(url.contains("X-Amz-Expires=900"), url);
    }

    @Test
    void theDeclaredSizeIsSignedIntoTheUrl() {
        String url = storage.presignUploadPart("interview", "interview-7/rec.webm", "u", 1, 5_242_880L,
                Duration.ofMinutes(15));
        assertTrue(url.toLowerCase().contains("content-length"), "content-length must be a signed header: " + url);
    }

    @Test
    void anEmptyPieceIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.presignUploadPart("interview", "f.webm", "u", 1, 0, Duration.ofMinutes(1)));
    }
}
