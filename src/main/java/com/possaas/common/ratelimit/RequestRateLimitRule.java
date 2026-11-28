package com.possaas.common.ratelimit;

import java.util.Objects;
import org.springframework.http.HttpMethod;

/** Limits requests matching {@code method} + {@code pathPattern} (Spring path pattern) per client address. */
public record RequestRateLimitRule(HttpMethod method, String pathPattern, RateLimitPolicy policy) {
    public RequestRateLimitRule {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(policy, "policy");
        if (pathPattern == null || pathPattern.isBlank()) throw new IllegalArgumentException("pathPattern is required");
    }
}
