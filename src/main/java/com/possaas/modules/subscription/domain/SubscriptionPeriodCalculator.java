package com.possaas.modules.subscription.domain;

import java.time.Instant;
import java.time.ZoneId;

/** Billing period [startAt, endAt): calendar months in the quote's billing zone; java.time clamps month ends. */
public final class SubscriptionPeriodCalculator {
    private SubscriptionPeriodCalculator() {}

    public static Instant endAt(Instant startAt, String billingZoneId, int billingCycleMonths) {
        if (billingCycleMonths < 1) throw new IllegalArgumentException("Billing cycle must be at least one month");
        return startAt.atZone(ZoneId.of(billingZoneId)).plusMonths(billingCycleMonths).toInstant();
    }
}
