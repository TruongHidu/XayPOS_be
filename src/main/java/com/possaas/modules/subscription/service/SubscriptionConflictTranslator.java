package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.HttpStatus;

/** Translates persistence conflicts without querying an already failed transaction. */
public final class SubscriptionConflictTranslator {
    private SubscriptionConflictTranslator() {}

    public static BusinessException translate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                if ("uq_restaurant_subscriptions_one_pending".equals(violation.getConstraintName())) {
                    return conflict("SUBSCRIPTION_PENDING_EXISTS", "Restaurant already has a pending subscription");
                }
                if ("uq_restaurant_subscriptions_one_active".equals(violation.getConstraintName())) {
                    return conflict("SUBSCRIPTION_OVERLAP", "Restaurant already has an active subscription");
                }
            }
        }
        return conflict("CONCURRENT_SUBSCRIPTION_UPDATE", "Subscription changed concurrently or violates a persistence constraint");
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
