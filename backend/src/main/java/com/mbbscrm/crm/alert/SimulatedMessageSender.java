package com.mbbscrm.crm.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fallback sender while no WhatsApp/SMS provider is configured: logs the message and reports it as SIMULATED,
 * so the whole pipeline (queueing, escalation, communication log) can be used and tested for free.
 * Add any other {@link MessageSender} bean (e.g. a Gupshup or MSG91 adapter) and the dispatcher uses that instead.
 */
@Component
public class SimulatedMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMessageSender.class);

    @Override
    public Result send(Channel channel, String recipient, String body) {
        log.info("[SIMULATED {}] to {}: {}", channel, mask(recipient), body);
        return Result.simulatedDelivery();
    }

    /** Keep full phone numbers out of logs. */
    private static String mask(String recipient) {
        return recipient.length() <= 4 ? "****" : "******" + recipient.substring(recipient.length() - 4);
    }
}
