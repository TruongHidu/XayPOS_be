package com.possaas.modules.subscriptionpurchase.application.port;

import java.time.Instant;
import java.util.Map;

/**
 * Provider transaction whose authenticity an inbound adapter has already proven. Contract for every source:
 * whole positive VND amount, UTC occurredAt, providerTransactionId stable across retries, providerAccountKey taken
 * from trusted configuration (never from the payload), payload limited to an allowlist without secrets.
 */
public record VerifiedPaymentEvent(
    String provider, String providerAccountKey, String providerTransactionId, Long providerWebhookId,
    String bankReference, String source, String payloadHash, Map<String, Object> payload,
    String direction, long amount, String currencyCode, String receiverAccount, String receiverSubAccount,
    String receiverBank, String code, String content, Instant occurredAt
) {
    public static final String DIRECTION_IN = "in";
    public static final String SOURCE_WEBHOOK = "WEBHOOK";
}
