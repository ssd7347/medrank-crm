package com.mbbscrm.crm.voice;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mbbscrm.crm.common.ApiException;

/**
 * Proves a tool or webhook request really came from the voice platform (spec 18.8 and 18.14.4).
 * <pre>
 *   X-Voice-Timestamp: seconds since 1970
 *   X-Voice-Signature: sha256= + hex( HMAC-SHA256( secret, timestamp + "." + raw request body ) )
 * </pre>
 * The timestamp is inside the signature, so an old request cannot be replayed with a fresh time. Requests
 * more than five minutes old, or any request while no secret is configured, are refused.
 */
@Component
public class VoiceSignatures {

    public static final String SIGNATURE_HEADER = "X-Voice-Signature";
    public static final String TIMESTAMP_HEADER = "X-Voice-Timestamp";
    static final long MAX_AGE_SECONDS = 300;

    private final VoiceProperties props;

    public VoiceSignatures(VoiceProperties props) {
        this.props = props;
    }

    public void verify(String rawBody, String signature, String timestamp) {
        if (!props.signingConfigured() || signature == null || timestamp == null) {
            throw unauthorised();
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw unauthorised();
        }
        if (Math.abs(Instant.now().getEpochSecond() - sentAt) > MAX_AGE_SECONDS) {
            throw unauthorised();
        }
        String expected = sign(props.hmacSecret(), timestamp.trim(), rawBody == null ? "" : rawBody);
        // Constant-time comparison, so the signature cannot be guessed one character at a time.
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8))) {
            throw unauthorised();
        }
    }

    public static String sign(String secret, String timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(
                    mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthorised() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid signature");
    }
}
