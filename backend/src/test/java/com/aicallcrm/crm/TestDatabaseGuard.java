package com.aicallcrm.crm;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Tests, the in-memory test CRM and the demo all start with the test classes, and the tests write data. So anything
 * started with them may use only an in-memory H2 database or a database whose name ends in "_test" (for running the
 * tests on PostgreSQL, README "Tests"). Checked before anything connects, so a real database (the dev one, or a
 * client's) is never touched by mistake. Registered in src/test/resources/META-INF/spring.factories.
 */
public class TestDatabaseGuard implements EnvironmentPostProcessor {

    private static final Pattern DB_NAME = Pattern.compile("^jdbc:[a-z0-9]+:(?://[^/]*/)?([^?;]+)");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (!allowed(url)) {
            String shown = url == null ? "no database" : url.replaceAll("[?;].*", "");
            throw new IllegalStateException("Refusing to start with the test classes on " + shown
                    + ": tests write data. Use an in-memory H2 database, or a database whose name ends in \"_test\".");
        }
    }

    /** An in-memory H2 database, or a database whose name ends in "_test". */
    static boolean allowed(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:h2:mem:")) {
            return true;
        }
        if (!u.startsWith("jdbc:postgresql://")) {
            return false;
        }
        Matcher m = DB_NAME.matcher(u);
        return m.find() && m.group(1).endsWith("_test");
    }
}
