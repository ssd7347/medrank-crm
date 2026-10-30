package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Plivo's V3 signature. The expected values come from a separate Python transcription of plivo-python's
 * utils/signature_v3.py, and the first base string is the worked example on Plivo's signature-validation page.
 */
class PlivoSignatureTest {

    private static final String TOKEN = "MATOKEN1234567890abcdef";
    private static final String NONCE = "12345678901234567890";

    private static Map<String, List<String>> form(String... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(kv[i + 1]));
        }
        return m;
    }

    @Test
    void theBaseStringMatchesPlivosDocumentedExample() {
        assertThat(PlivoSignature.base("POST", "https://example.com/abcd?foo=bar", form("Digits", "1234",
                "To", "+15555555555", "From", "+15551111111", "CallUUID", "4vbcpem8-0u46-x1ha-9af1-438vc92bf374")))
                .isEqualTo("https://example.com/abcd?foo=bar.CallUUID4vbcpem8-0u46-x1ha-9af1-438vc92bf374Digits1234"
                        + "From+15551111111To+15555555555");
    }

    @Test
    void signaturesMatchThePythonReference() {
        String answer = "https://crm.example.in/api/voice/hooks/plivo/answer?call_id=6f1c2b8e-0000-4000-8000-000000000001";
        Map<String, List<String>> answered = form("CallUUID", "abc-123", "From", "+919812345678", "To", "+918045671234",
                "Direction", "outbound", "CallStatus", "in-progress");
        assertThat(PlivoSignature.base("POST", answer, answered)).isEqualTo(answer
                + ".CallStatusin-progressCallUUIDabc-123DirectionoutboundFrom+919812345678To+918045671234");
        assertThat(PlivoSignature.sign(TOKEN, PlivoSignature.base("POST", answer, answered), NONCE))
                .isEqualTo("3R2+wql2r8z/+hSzdJS/mGHd/t2+ZOw8mXC9l5nwjQo=");

        // Fields but no query: "?" and no ".".
        String hangup = "https://crm.example.in/api/voice/hooks/plivo/hangup";
        Map<String, List<String>> hung = form("CallUUID", "abc-123", "Duration", "42", "HangupCause", "NORMAL_CLEARING");
        assertThat(PlivoSignature.base("POST", hangup, hung)).isEqualTo(hangup + "?CallUUIDabc-123Duration42HangupCauseNORMAL_CLEARING");
        assertThat(PlivoSignature.sign(TOKEN, PlivoSignature.base("POST", hangup, hung), NONCE))
                .isEqualTo("4tXoTpOiKZElBwRVJrUZvTnthpAYT4z5/kQHvUzhgn4=");

        // Neither: the bare URL.
        String desk = "https://crm.example.in/api/voice/hooks/plivo/desk";
        assertThat(PlivoSignature.sign(TOKEN, PlivoSignature.base("POST", desk, Map.of()), NONCE))
                .isEqualTo("BldF3REnTi+LpUDm6JJubNcUKqRvtCo+7dU1wGTAdN8=");
    }

    @Test
    void theStreamTokenMatchesTheSpeechEngine() {
        // voice-engine/test_phone.py asserts the same value for phone.stream_token.
        assertThat(PlivoHooksController.streamToken("test-only-voice-secret-0123456789-abcdefgh",
                java.util.UUID.fromString("6f1c2b8e-0000-4000-8000-000000000001")))
                .isEqualTo("c4af2d96e8c7c170948d98741c6e3201f8a57ae74c2642903899926cc2e7b5df");
    }

    @Test
    void validChecksEverySignatureInTheHeaderAndNothingElse() {
        String desk = "https://crm.example.in/api/voice/hooks/plivo/desk";
        String good = "BldF3REnTi+LpUDm6JJubNcUKqRvtCo+7dU1wGTAdN8=";
        assertThat(PlivoSignature.valid(TOKEN, "POST", desk, Map.of(), NONCE, good)).isTrue();
        assertThat(PlivoSignature.valid(TOKEN, "POST", desk, Map.of(), NONCE, "old-token-signature, " + good)).isTrue();
        assertThat(PlivoSignature.valid(TOKEN, "POST", desk, Map.of(), "other-nonce", good)).isFalse();
        assertThat(PlivoSignature.valid("another-token", "POST", desk, Map.of(), NONCE, good)).isFalse();
        assertThat(PlivoSignature.valid(TOKEN, "POST", desk + "?x=1", Map.of(), NONCE, good)).isFalse();
        assertThat(PlivoSignature.valid(TOKEN, "POST", desk, form("CallUUID", "x"), NONCE, good)).isFalse();
        assertThat(PlivoSignature.valid("", "POST", desk, Map.of(), NONCE, good)).isFalse();
        assertThat(PlivoSignature.valid(TOKEN, "POST", desk, Map.of(), NONCE, null)).isFalse();
    }
}
