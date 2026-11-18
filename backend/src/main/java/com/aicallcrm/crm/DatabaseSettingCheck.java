package com.aicallcrm.crm;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Stops the start, before anything connects, when the database address is missing, with a message that says which
 * setting to fill in. Without it a forgotten DB_URL ends in "'url' must start with "jdbc"" deep in a stack trace.
 * Registered in META-INF/spring.factories.
 */
public class DatabaseSettingCheck implements EnvironmentPostProcessor {

    static final String MESSAGE = "DB_URL is not set. Set it to this CRM's PostgreSQL database, for example "
            + "jdbc:postgresql://localhost:5432/ai_call_crm (docs/new-customer-setup.md).";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url;
        try {
            url = environment.getProperty("spring.datasource.url");
        } catch (IllegalArgumentException unresolved) {
            url = null;
        }
        if (url == null || !url.trim().startsWith("jdbc:")) {
            throw new IllegalStateException(MESSAGE);
        }
    }
}
