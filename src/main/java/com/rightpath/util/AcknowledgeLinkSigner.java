package com.rightpath.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs the "confirm your slot" link in the acknowledgement mail.
 *
 * <p>The link is opened from a mail client, so the candidate has no login and no token. Without a signature it would
 * have to be open to anyone, and anyone who knew an applicant's email and a job code could confirm the slot for them.
 * The signature is an HMAC of the job prefix and email under the server's secret: it can be produced only by the
 * server, and it ties the link to exactly one application.</p>
 */
@Component
public class AcknowledgeLinkSigner {

    private final byte[] key;

    public AcknowledgeLinkSigner(@Value("${jwt.token:}") String secret) {
        this.key = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
    }

    /** The signature to put on the link for this application, as lower-case hex. */
    public String sign(String jobPrefix, String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // An unset secret still has to give a key; the link is then only as strong as the empty secret, which
            // the deployment is expected to have set.
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[] { 0 } : key, "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    (jobPrefix + "|" + email.toLowerCase()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the acknowledgement link", e);
        }
    }

    /** Whether the signature on a link is the one this server would have issued. Constant-time. */
    public boolean verify(String jobPrefix, String email, String signature) {
        if (jobPrefix == null || email == null || signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(jobPrefix, email).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
