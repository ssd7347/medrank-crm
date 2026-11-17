package com.aicallcrm.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Anything started with the test classes may use only an in-memory database or one whose name ends in "_test". */
class TestDatabaseGuardTest {

    @Test
    void onlyInMemoryOrTestDatabasesAreAllowed() {
        assertThat(TestDatabaseGuard.allowed("jdbc:h2:mem:crm;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")).isTrue();
        assertThat(TestDatabaseGuard.allowed("jdbc:postgresql://localhost:5432/ai_call_crm_test")).isTrue();
        assertThat(TestDatabaseGuard.allowed("jdbc:postgresql://localhost:5432/AI_CALL_CRM_TEST?ssl=false")).isTrue();
        for (String real : new String[] {
                "jdbc:postgresql://localhost:5432/ai_call_crm",              // the dev database
                "jdbc:postgresql://db.example.com:5432/crm",                 // a client's
                "jdbc:postgresql://localhost:5432/ai_call_crm?x=_test",      // "_test" only in the options
                "jdbc:postgresql://localhost:5432/test_ai_call_crm",         // "test" at the wrong end
                "jdbc:postgresql://localhost:5432/ai_call_crm_test/../ai_call_crm",
                "jdbc:h2:file:./data/crm",                                   // H2 kept on disk
                "", "   "}) {
            assertThat(TestDatabaseGuard.allowed(real)).as(real).isFalse();
        }
        assertThat(TestDatabaseGuard.allowed(null)).isFalse();
    }

    @Test
    void itStopsTheStartBeforeAnythingConnects() {
        StandardEnvironment env = empty();
        env.getPropertySources().addFirst(new MapPropertySource("t",
                Map.of("spring.datasource.url", "jdbc:postgresql://localhost:5432/ai_call_crm?password=secret")));
        assertThatThrownBy(() -> new TestDatabaseGuard().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:postgresql://localhost:5432/ai_call_crm:")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> new TestDatabaseGuard().postProcessEnvironment(empty(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no database");
    }

    /** Without the machine's own settings, which may hold a SPRING_DATASOURCE_URL (README "Tests"). */
    private static StandardEnvironment empty() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return env;
    }
}
