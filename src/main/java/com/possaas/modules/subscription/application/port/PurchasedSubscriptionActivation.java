package com.possaas.modules.subscription.application.port;

import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot.FeatureGrant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Grants a paid package to a restaurant from the frozen purchase quote, never from the live catalog.
 * Caller holds the restaurant then purchase locks and owns the transaction (MANDATORY).
 */
public interface PurchasedSubscriptionActivation {
    /** @throws ActivationBlockedException when the restaurant already has an active or pending subscription */
    Result activate(Command command);

    record Command(UUID restaurantId, UUID purchaseId, UUID packageId, String packageCode, BigDecimal priceAmount,
        String currencyCode, int billingCycleMonths, String billingZoneId, List<FeatureGrant> features, Long maxStaff,
        Instant activatedAt) {}

    record Result(UUID subscriptionId, Instant startAt, Instant endAt) {}

    class ActivationBlockedException extends RuntimeException {
        private final String reason;
        public ActivationBlockedException(String reason) { super("Subscription activation blocked: " + reason); this.reason = reason; }
        public String reason() { return reason; }
    }
}
