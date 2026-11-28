package com.possaas.infrastructure.ratelimit;

import com.possaas.common.ratelimit.RateLimitDecision;
import com.possaas.common.ratelimit.RateLimitPolicy;
import com.possaas.common.ratelimit.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Allows every hit. Active only with {@code app.rate-limit.enabled=false} (tests, emergency switch-off). */
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "false")
public class NoopRateLimiter implements RateLimiter {
    @Override
    public RateLimitDecision tryAcquire(RateLimitPolicy policy, String key) {
        return RateLimitDecision.allowed(policy.limit());
    }

    @Override
    public RateLimitDecision check(RateLimitPolicy policy, String key) {
        return RateLimitDecision.allowed(policy.limit());
    }

    @Override
    public void reset(RateLimitPolicy policy, String key) {
        // Nothing is counted, so there is nothing to forget.
    }
}
