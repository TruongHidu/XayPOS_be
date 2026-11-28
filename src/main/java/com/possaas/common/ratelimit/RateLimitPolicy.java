package com.possaas.common.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * At most {@code limit} hits per key inside one {@code window}. The name namespaces counters, so two policies
 * with different names never share a bucket even for the same key.
 */
public record RateLimitPolicy(String name, int limit, Duration window) {
    public RateLimitPolicy {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Rate limit policy name is required");
        if (limit < 1) throw new IllegalArgumentException("Rate limit '" + name + "' must allow at least one hit");
        Objects.requireNonNull(window, "Rate limit '" + name + "' needs a window");
        if (window.isNegative() || window.isZero())
            throw new IllegalArgumentException("Rate limit '" + name + "' window must be positive");
    }
}
