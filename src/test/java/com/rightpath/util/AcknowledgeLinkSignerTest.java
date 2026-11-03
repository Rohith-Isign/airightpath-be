package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The confirm-your-slot link works only for the application it was issued for. */
class AcknowledgeLinkSignerTest {

    private final AcknowledgeLinkSigner signer = new AcknowledgeLinkSigner("a-test-secret");

    @Test
    void aSignatureVerifiesForTheSameApplication() {
        String token = signer.sign("JOB-1", "Jane@Example.com");

        assertTrue(signer.verify("JOB-1", "jane@example.com", token));
    }

    @Test
    void aSignatureDoesNotVerifyForAnotherEmailOrJob() {
        String token = signer.sign("JOB-1", "jane@example.com");

        assertFalse(signer.verify("JOB-1", "someone@example.com", token));
        assertFalse(signer.verify("JOB-2", "jane@example.com", token));
    }

    @Test
    void aMissingOrWrongTokenIsRefused() {
        assertFalse(signer.verify("JOB-1", "jane@example.com", null));
        assertFalse(signer.verify("JOB-1", "jane@example.com", "abc"));
        assertFalse(new AcknowledgeLinkSigner("another-secret")
                .verify("JOB-1", "jane@example.com", signer.sign("JOB-1", "jane@example.com")));
    }
}
