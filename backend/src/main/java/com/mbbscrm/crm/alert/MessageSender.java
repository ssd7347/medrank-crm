package com.mbbscrm.crm.alert;

/**
 * Sends one message through a provider (WhatsApp BSP, SMS gateway, email). Implementations must be
 * swappable without touching business logic (spec section 7, integration layer).
 */
public interface MessageSender {

    record Result(boolean delivered, boolean simulated, String providerRef, String error) {
        public static Result delivered(String ref) {
            return new Result(true, false, ref, null);
        }

        public static Result simulatedDelivery() {
            return new Result(true, true, null, null);
        }

        public static Result failure(String error) {
            return new Result(false, false, null, error);
        }
    }

    Result send(Channel channel, String recipient, String body);
}
