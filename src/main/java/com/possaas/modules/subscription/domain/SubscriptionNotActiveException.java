package com.possaas.modules.subscription.domain;

import com.possaas.common.exception.BusinessException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * No subscription is in effect, with why: the restaurant never had one, or its last one has run out. Clients
 * keep matching on SUBSCRIPTION_NOT_ACTIVE; the reason only lets them say "subscribe" or "renew".
 */
public class SubscriptionNotActiveException extends BusinessException {
    public enum Reason { NONE, EXPIRED }

    private final Reason reason;

    public SubscriptionNotActiveException(Reason reason) {
        super(HttpStatus.FORBIDDEN, "SUBSCRIPTION_NOT_ACTIVE",
            "No active subscription is effective for this restaurant");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public Object details() {
        return Map.of("reason", reason.name());
    }
}
