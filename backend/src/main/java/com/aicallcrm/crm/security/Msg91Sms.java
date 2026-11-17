package com.aicallcrm.crm.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends the sign-in code by SMS through MSG91's Flow API, which sends a template from MSG91's SMS → Templates (the
 * DLT-approved one) with the code in its variable. A "success" reply means MSG91 accepted the message, not that the
 * phone got it: MSG91's SMS log says that. Neither the code nor the whole number is ever logged.
 */
@Component
public class Msg91Sms {

    private static final Logger log = LoggerFactory.getLogger(Msg91Sms.class);

    private final SmsProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    public Msg91Sms(SmsProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean configured() {
        return props.configured();
    }

    /**
     * {@link #sendCode} without waiting for MSG91, so the login page's reply takes as long for a registered number as
     * for an unknown one (which gets no SMS): its timing must not tell anyone which numbers have an account.
     */
    public void sendCodeInBackground(String phone, String code) {
        background.execute(() -> sendCode(phone, code));
    }

    @PreDestroy
    void stop() {
        background.shutdown();
    }

    /** {@code phone}: a 10-digit Indian mobile number. True when MSG91 accepted the message. */
    public boolean sendCode(String phone, String code) {
        if (!configured() || phone == null || !phone.matches("[0-9]{10}") || code == null || !code.matches("[0-9]{6}")) {
            return false;
        }
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("mobiles", "91" + phone);
        recipient.put(props.otpVariable(), code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template_id", props.templateId());
        body.put("short_url", "0");
        body.put("recipients", List.of(recipient));
        String base = props.apiBase().endsWith("/") ? props.apiBase().substring(0, props.apiBase().length() - 1)
                : props.apiBase();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/flow"))
                .timeout(Duration.ofSeconds(8))
                .header("authkey", props.authKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        String to = "******" + phone.substring(6);
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Object type = null;
            Object message = null;
            if (res.statusCode() / 100 == 2) {
                try {
                    Map<?, ?> reply = mapper.readValue(res.body(), Map.class);
                    type = reply.get("type");
                    message = reply.get("message");
                } catch (RuntimeException notJson) {
                    // An answer that is not JSON is a failure, below.
                }
            }
            if ("success".equals(type)) {
                log.info("Sign-in code SMS to {} accepted by MSG91 (request {})", to, message);
                return true;
            }
            log.warn("Sign-in code SMS to {} not accepted by MSG91: HTTP {}, {}", to, res.statusCode(),
                    message != null ? message : "no explanation");
            return false;
        } catch (java.io.IOException e) {
            log.warn("Sign-in code SMS to {}: MSG91 could not be reached ({})", to, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
