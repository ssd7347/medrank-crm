package com.aicallcrm.crm.alert;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the background jobs (deadline scans, escalation, message dispatch). Tests switch them off with
 * {@code app.alerts.scheduling-enabled=false} and call the jobs directly. On a sleep-when-idle database such
 * as Neon, lengthen the intervals when the account is quiet.
 */
@Configuration
@EnableAsync
@EnableScheduling
@ConditionalOnProperty(name = "app.alerts.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AlertConfig {
}
