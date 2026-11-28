package com.possaas.common.ratelimit;

/**
 * Counts hits per (policy, key). Implementations never throw when a limit is reached: they return a
 * {@link RateLimitDecision} and the caller decides how to reject (HTTP 429 in a filter, an exception in a service).
 * Keys are opaque; implementations may store only a digest of them.
 */
public interface RateLimiter {
    /** Records one hit and reports whether it was within the limit. A rejected hit is not counted. */
    RateLimitDecision tryAcquire(RateLimitPolicy policy, String key);

    /** Reports whether the next hit would be allowed, without recording anything. */
    RateLimitDecision check(RateLimitPolicy policy, String key);

    /** Forgets every hit recorded for the key, e.g. after a successful login. */
    void reset(RateLimitPolicy policy, String key);
}
