package com.aicallcrm.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** What the shipped application.properties does when a server forgets a setting. */
class ProductionSettingsTest {

    static Properties shipped() throws Exception {
        Properties p = new Properties();
        try (InputStream in = ProductionSettingsTest.class.getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        return p;
    }

    @Test
    void withoutDbUrlTheCrmDoesNotGuessADatabase() throws Exception {
        // It used to fall back to jdbc:postgresql://localhost:5432/mbbs_crm, another project's database.
        assertThat(shipped().getProperty("spring.datasource.url")).isEqualTo("${DB_URL}");
    }

    @Test
    void aMissingDbUrlStopsTheStartWithAPlainMessage() {
        for (Map<String, Object> settings : List.<Map<String, Object>>of(
                Map.of(), Map.of("spring.datasource.url", "${DB_URL}"), Map.of("spring.datasource.url", "  "))) {
            assertThatThrownBy(() -> new DatabaseSettingCheck().postProcessEnvironment(env(settings), null))
                    .as(settings.toString())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("DB_URL is not set.");
        }
        new DatabaseSettingCheck().postProcessEnvironment(
                env(Map.of("spring.datasource.url", "jdbc:postgresql://localhost:5432/ai_call_crm")), null);
        new DatabaseSettingCheck().postProcessEnvironment(
                env(Map.of("spring.datasource.url", "jdbc:h2:mem:crm;MODE=PostgreSQL")), null);
    }

    /** Only these settings: not the machine's own, which may hold a DB_URL. */
    private static StandardEnvironment env(Map<String, Object> settings) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("settings", settings));
        return env;
    }

    @Test
    void theSafeDefaultsHold() throws Exception {
        Properties p = shipped();
        assertThat(p.getProperty("app.cookie.secure")).isEqualTo("${COOKIE_SECURE:true}");
        assertThat(p.getProperty("app.otp.show-on-screen")).isEqualTo("${OTP_SHOW_ON_SCREEN:false}");
        assertThat(p.getProperty("server.error.include-stacktrace")).isEqualTo("never");
    }
}
