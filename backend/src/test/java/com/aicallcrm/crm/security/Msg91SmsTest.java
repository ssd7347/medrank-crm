package com.aicallcrm.crm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

/** The request MSG91's Flow API gets, and what counts as sent. A fake MSG91 on this computer; nothing leaves it. */
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings("unchecked")
class Msg91SmsTest {

    record Got(String method, String path, String authKey, String contentType, Map<String, Object> body) {
    }

    final ObjectMapper mapper = new ObjectMapper();
    final List<Got> got = new CopyOnWriteArrayList<>();
    HttpServer fake;
    volatile int status = 200;
    volatile long delayMs = 0;
    volatile String reply = "{\"type\":\"success\",\"message\":\"3f6a-request\"}";

    @BeforeEach
    void start() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            got.add(new Got(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("authkey"), ex.getRequestHeaders().getFirst("Content-Type"),
                    body.isEmpty() ? Map.of() : json(body)));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
    }

    @AfterEach
    void stop() {
        fake.stop(0);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> json(String body) {
        return mapper.readValue(body, Map.class);
    }

    Msg91Sms sms(String provider, String variable) {
        return new Msg91Sms(new SmsProperties(provider, "test-auth-key-not-real", "tmpl-sign-in", variable,
                "http://127.0.0.1:" + fake.getAddress().getPort() + "/api/v5/"), mapper);
    }

    @Test
    void sendsTheApprovedTemplateWithTheCodeToTheIndianNumber(CapturedOutput output) {
        assertThat(sms("msg91", "OTP").sendCode("9840012345", "482913")).isTrue();

        assertThat(got).hasSize(1);
        Got g = got.get(0);
        assertThat(g.method()).isEqualTo("POST");
        assertThat(g.path()).isEqualTo("/api/v5/flow");
        assertThat(g.authKey()).isEqualTo("test-auth-key-not-real");
        assertThat(g.contentType()).isEqualTo("application/json");
        assertThat(g.body()).containsEntry("template_id", "tmpl-sign-in").containsEntry("short_url", "0");
        assertThat((List<Object>) g.body().get("recipients"))
                .containsExactly(Map.of("mobiles", "919840012345", "OTP", "482913"));

        // The log says it was accepted, never the code or the whole number.
        assertThat(output).contains("******2345").contains("3f6a-request")
                .doesNotContain("482913").doesNotContain("9840012345");
    }

    @Test
    void theLoginPageDoesNotWaitForMsg91() throws Exception {
        delayMs = 2000;
        long start = System.nanoTime();
        sms("msg91", "OTP").sendCodeInBackground("9840012345", "482913");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
        for (int i = 0; i < 100 && got.isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertThat(got).hasSize(1);
    }

    @Test
    void theTemplatesOwnVariableNameIsUsed() {
        assertThat(sms("MSG91", "##otp_code##").sendCode("9840012345", "000123")).isTrue();
        assertThat((List<Object>) got.get(0).body().get("recipients"))
                .containsExactly(Map.of("mobiles", "919840012345", "otp_code", "000123"));
    }

    @Test
    void onlyASuccessReplyCountsAsSent(CapturedOutput output) {
        // MSG91 can answer HTTP 200 with an error inside.
        reply = "{\"type\":\"error\",\"message\":\"Authentication failure\"}";
        assertThat(sms("msg91", "OTP").sendCode("9840012345", "482913")).isFalse();
        assertThat(output).contains("Authentication failure").doesNotContain("482913");

        status = 401;
        reply = "{\"type\":\"success\"}";
        assertThat(sms("msg91", "OTP").sendCode("9840012345", "482913")).isFalse();

        status = 200;
        reply = "<html>gateway</html>";
        assertThat(sms("msg91", "OTP").sendCode("9840012345", "482913")).isFalse();
        assertThat(got).hasSize(3);
    }

    @Test
    void nothingIsSentWithoutTheSettingsOrForABadNumber() {
        assertThat(sms("", "OTP").sendCode("9840012345", "482913")).isFalse();
        assertThat(new Msg91Sms(new SmsProperties("msg91", "", "tmpl", "OTP", "http://127.0.0.1:1/api/v5"), mapper)
                .sendCode("9840012345", "482913")).isFalse();
        assertThat(new Msg91Sms(new SmsProperties("msg91", "key", "", "OTP", "http://127.0.0.1:1/api/v5"), mapper)
                .sendCode("9840012345", "482913")).isFalse();
        assertThat(sms("msg91", "OTP").sendCode("+919840012345", "482913")).isFalse();
        assertThat(sms("msg91", "OTP").sendCode("98400", "482913")).isFalse();
        assertThat(sms("msg91", "OTP").sendCode("9840012345", "48291")).isFalse();
        assertThat(got).isEmpty();
    }

    @Test
    void aMistypedProviderOrVariableStopsTheStart() {
        assertThatThrownBy(() -> new SmsProperties("msg 91", "k", "t", "OTP", "https://x"))
                .hasMessageContaining("OTP_SMS_PROVIDER");
        assertThatThrownBy(() -> new SmsProperties("msg91", "k", "t", "OTP CODE", "https://x"))
                .hasMessageContaining("MSG91_OTP_VARIABLE");
        assertThat(new SmsProperties("  ", "", "", "OTP", "https://x").configured()).isFalse();
    }
}
