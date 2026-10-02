package com.possaas.modules.subscription.domain;

import java.math.BigDecimal;

/** Numeric contract shared by catalog validation and snapshot capacity enforcement. */
public final class FeatureLimitValues {
    private FeatureLimitValues() {}

    public static long positiveLong(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected a number");
        long result = new BigDecimal(value.toString()).longValueExact();
        if (result <= 0) throw new IllegalArgumentException("Expected a positive integer");
        return result;
    }
}
