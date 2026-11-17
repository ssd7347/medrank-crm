package com.aicallcrm.crm.security;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code app.otp.sms.*}: the SMS provider that sends staff their sign-in code. Each customer's copy of
 * the CRM uses its own provider account, its own DLT registration (sender ID and an approved template) and its own
 * key; see docs/new-customer-setup.md. Blank provider: no SMS is sent.
 */
@ConfigurationProperties(prefix = "app.otp.sms")
public record SmsProperties(
        /** "" (none) or "msg91". */
        @DefaultValue("") String provider,
        /** The MSG91 account's auth key. Never in code, chat or email; only on the server. */
        @DefaultValue("") String authKey,
        /** The MSG91 template id of the DLT-approved sign-in template (SMS → Templates), for the Flow API. */
        @DefaultValue("") String templateId,
        /** The template's variable that holds the code: OTP for a template written with ##OTP##. */
        @DefaultValue("OTP") String otpVariable,
        @DefaultValue("https://control.msg91.com/api/v5") String apiBase) {

    public SmsProperties {
        provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!provider.isEmpty() && !provider.equals("msg91")) {
            throw new IllegalArgumentException("OTP_SMS_PROVIDER must be blank or msg91, not " + provider);
        }
        otpVariable = otpVariable == null ? "" : otpVariable.trim().replace("#", "");
        if (!otpVariable.matches("[A-Za-z0-9_]{1,40}")) {
            throw new IllegalArgumentException("MSG91_OTP_VARIABLE must be the template's variable name, e.g. OTP");
        }
    }

    /** MSG91 is chosen and has its key and template. */
    public boolean configured() {
        return provider.equals("msg91") && !authKey.isBlank() && !templateId.isBlank() && !apiBase.isBlank();
    }
}
