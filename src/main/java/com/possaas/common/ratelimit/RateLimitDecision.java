package com.possaas.common.ratelimit;

import java.time.Duration;

/**
 * Outcome of a rate-limit lookup. {@code remaining} is how many more hits the current window accepts;
 * {@code retryAfter} is zero when allowed and otherwise the time until the window reopens.
 */
public record RateLimitDecision(boolean allowed, int remaining, Duration retryAfter) {
    public static RateLimitDecision allowed(int remaining) {
        return new RateLimitDecision(true, Math.max(remaining, 0), Duration.ZERO);
    }

    public static RateLimitDecision rejected(Duration retryAfter) {
        return new RateLimitDecision(false, 0, retryAfter);
    }

    /** Whole seconds for the {@code Retry-After} header: rounded up and never below one. */
    public long retryAfterSeconds() {
        return retryAfterSecondsOf(retryAfter);
    }

    static long retryAfterSecondsOf(Duration retryAfter) {
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) return 1;
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        return Math.max(seconds, 1);
    }
}
