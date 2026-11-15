package com.aicallcrm.crm.voice;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({VoiceProperties.class, AiProperties.class, PlivoProperties.class})
public class VoiceConfig {

    /** The time the hand-over rules (desk hours) read; tests replace it to check a weekday on any day. */
    @Bean
    Clock voiceClock() {
        return Clock.systemUTC();
    }
}
