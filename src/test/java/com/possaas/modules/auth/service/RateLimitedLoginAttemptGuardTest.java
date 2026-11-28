package com.possaas.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.ratelimit.RateLimitExceededException;
import com.possaas.config.AuthRateLimitProperties;
import com.possaas.config.RateLimitProperties;
import com.possaas.infrastructure.ratelimit.InMemoryFixedWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RateLimitedLoginAttemptGuardTest {
    private final RateLimitedLoginAttemptGuard guard = guard(3);

    @Test
    void locksAfterTheConfiguredNumberOfFailures() {
        guard.recordFailure("owner@example.com");
        guard.recordFailure("owner@example.com");
        assertThatCode(() -> guard.assertNotLocked("owner@example.com")).doesNotThrowAnyException();

        guard.recordFailure("owner@example.com");

        assertThatThrownBy(() -> guard.assertNotLocked("owner@example.com"))
            .isInstanceOfSatisfying(RateLimitExceededException.class, locked -> {
                assertThat(locked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(locked.getCode()).isEqualTo("LOGIN_TEMPORARILY_LOCKED");
                assertThat(locked.retryAfterSeconds()).isEqualTo(15 * 60);
            });
        assertThatCode(() -> guard.assertNotLocked("someone-else@example.com")).doesNotThrowAnyException();
    }

    @Test
    void successClearsEarlierFailures() {
        guard.recordFailure("owner@example.com");
        guard.recordFailure("owner@example.com");
        guard.recordSuccess("owner@example.com");
        guard.recordFailure("owner@example.com");
        guard.recordFailure("owner@example.com");

        assertThatCode(() -> guard.assertNotLocked("owner@example.com")).doesNotThrowAnyException();
    }

    private static RateLimitedLoginAttemptGuard guard(int failures) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC);
        var limiter = new InMemoryFixedWindowRateLimiter(clock, new RateLimitProperties());
        var properties = new AuthRateLimitProperties();
        properties.setLoginFailures(new AuthRateLimitProperties.Rule(failures, Duration.ofMinutes(15)));
        return new RateLimitedLoginAttemptGuard(limiter, properties);
    }
}
