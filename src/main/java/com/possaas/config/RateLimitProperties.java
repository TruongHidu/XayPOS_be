package com.possaas.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Shared request/attempt rate limiter (see {@code common.ratelimit.RateLimiter}). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
    /** false swaps in a limiter that allows everything (tests, emergency switch-off). */
    private boolean enabled = true;
    /** Upper bound of live counters kept in memory for each policy. */
    private int maxEntriesPerPolicy = 20_000;
}
