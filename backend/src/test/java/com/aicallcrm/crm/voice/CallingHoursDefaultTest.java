package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * AI calls go out from 9 AM to 6 PM, Indian time (decided 17 Nov 2026), both as shipped in application.properties
 * and when the setting is missing altogether. Staff desk hours stay 9:30 AM to 6:30 PM. The test profile widens the
 * window so other tests can run at any hour, so this reads the shipped settings directly.
 */
class CallingHoursDefaultTest {

    @Test
    void aiCallsGoOutFromNineToSixAsShipped() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new PropertiesPropertySourceLoader().load("shipped", new ClassPathResource("application.properties"))
                .forEach(env.getPropertySources()::addLast);
        VoiceProperties shipped = Binder.get(env).bind("app.voice", VoiceProperties.class).get();
        assertThat(shipped.windowStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(shipped.windowEnd()).isEqualTo(LocalTime.of(18, 0));
        assertThat(shipped.deskOpen()).isEqualTo(LocalTime.of(9, 30));
        assertThat(shipped.deskClose()).isEqualTo(LocalTime.of(18, 30));
    }

    @Test
    void withNoSettingAtAllItIsStillNineToSix() {
        VoiceProperties bare = new Binder(new MapConfigurationPropertySource(Map.of("app.voice.enabled", "true")))
                .bind("app.voice", VoiceProperties.class).get();
        assertThat(bare.windowStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(bare.windowEnd()).isEqualTo(LocalTime.of(18, 0));
    }
}
