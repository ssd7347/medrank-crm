package com.mbbscrm.crm.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under the {@code app.*} prefix, all driven by environment variables in production. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cookie cookie, Bootstrap bootstrap) {

    public record Jwt(String secret, Duration accessTtl, Duration refreshTtl) {
    }

    public record Cookie(boolean secure) {
    }

    /** First-run super admin, created only when the user table is empty. */
    public record Bootstrap(String adminEmail, String adminPassword, String adminName) {
    }
}
