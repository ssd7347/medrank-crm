package com.aicallcrm.crm.voice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({VoiceProperties.class, AiProperties.class})
public class VoiceConfig {
}
