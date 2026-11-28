package com.possaas.modules.auth.service;

import com.possaas.common.ratelimit.RateLimitDecision;
import com.possaas.common.ratelimit.RateLimitExceededException;
import com.possaas.common.ratelimit.RateLimitPolicy;
import com.possaas.common.ratelimit.RateLimiter;
import com.possaas.config.AuthRateLimitProperties;
import com.possaas.modules.auth.application.port.LoginAttemptGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Counts wrong passwords per e-mail with the shared {@link RateLimiter}. Lock events are only logged, never
 * audited to the database: writing a row for every blocked attempt would hand the attacker the load we avoid.
 */
@Slf4j
@Component
public class RateLimitedLoginAttemptGuard implements LoginAttemptGuard {
    static final String LOCKED_CODE = "LOGIN_TEMPORARILY_LOCKED";
    static final String LOCKED_MESSAGE = "Too many failed login attempts. Please try again later.";

    private final RateLimiter limiter;
    private final RateLimitPolicy failures;

    public RateLimitedLoginAttemptGuard(RateLimiter limiter, AuthRateLimitProperties properties) {
        this.limiter = limiter;
        this.failures = properties.getLoginFailures().toPolicy("auth.login-failures");
    }

    @Override
    public void assertNotLocked(String email) {
        RateLimitDecision decision = limiter.check(failures, email);
        if (!decision.allowed()) throw new RateLimitExceededException(LOCKED_CODE, LOCKED_MESSAGE, decision.retryAfter());
    }

    @Override
    public void recordFailure(String email) {
        RateLimitDecision decision = limiter.tryAcquire(failures, email);
        // A rejected decision here means the store is full: the per-address limit still applies, so carry on.
        if (decision.allowed() && decision.remaining() == 0) {
            log.warn("Login temporarily locked after {} failed attempts within {}", failures.limit(), failures.window());
        }
    }

    @Override
    public void recordSuccess(String email) {
        limiter.reset(failures, email);
    }
}
