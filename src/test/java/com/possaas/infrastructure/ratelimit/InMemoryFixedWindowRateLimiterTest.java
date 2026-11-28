package com.possaas.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.ratelimit.RateLimitDecision;
import com.possaas.common.ratelimit.RateLimitPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryFixedWindowRateLimiterTest {
    private static final RateLimitPolicy TWO_PER_MINUTE = new RateLimitPolicy("test.two", 2, Duration.ofMinutes(1));
    private final MutableClock clock = new MutableClock();

    @Test
    void rejectsOnceTheLimitIsReachedAndReportsTimeLeft() {
        var limiter = new InMemoryFixedWindowRateLimiter(clock, 100);

        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "a")).isEqualTo(RateLimitDecision.allowed(1));
        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "a")).isEqualTo(RateLimitDecision.allowed(0));
        RateLimitDecision rejected = limiter.tryAcquire(TWO_PER_MINUTE, "a");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(40));
        assertThat(rejected.retryAfterSeconds()).isEqualTo(40);
    }

    @Test
    void anExpiredWindowStartsAFreshCount() {
        var limiter = new InMemoryFixedWindowRateLimiter(clock, 100);
        limiter.tryAcquire(TWO_PER_MINUTE, "a");
        limiter.tryAcquire(TWO_PER_MINUTE, "a");

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "a")).isEqualTo(RateLimitDecision.allowed(1));
    }

    @Test
    void checkNeverCountsAndResetForgetsTheKey() {
        var limiter = new InMemoryFixedWindowRateLimiter(clock, 100);
        assertThat(limiter.check(TWO_PER_MINUTE, "a")).isEqualTo(RateLimitDecision.allowed(2));
        limiter.tryAcquire(TWO_PER_MINUTE, "a");
        limiter.check(TWO_PER_MINUTE, "a");
        limiter.check(TWO_PER_MINUTE, "a");
        assertThat(limiter.check(TWO_PER_MINUTE, "a")).isEqualTo(RateLimitDecision.allowed(1));

        limiter.tryAcquire(TWO_PER_MINUTE, "a");
        assertThat(limiter.check(TWO_PER_MINUTE, "a").allowed()).isFalse();

        limiter.reset(TWO_PER_MINUTE, "a");
        assertThat(limiter.check(TWO_PER_MINUTE, "a")).isEqualTo(RateLimitDecision.allowed(2));
    }

    @Test
    void keysAndPoliciesAreCountedIndependently() {
        var limiter = new InMemoryFixedWindowRateLimiter(clock, 100);
        var other = new RateLimitPolicy("test.other", 1, Duration.ofMinutes(1));
        limiter.tryAcquire(TWO_PER_MINUTE, "a");
        limiter.tryAcquire(TWO_PER_MINUTE, "a");

        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "a").allowed()).isFalse();
        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "b").allowed()).isTrue();
        assertThat(limiter.tryAcquire(other, "a").allowed()).isTrue();
    }

    @Test
    void aFullStoreRejectsNewKeysButNeverEvictsLiveCounters() {
        var limiter = new InMemoryFixedWindowRateLimiter(clock, 1);
        limiter.tryAcquire(TWO_PER_MINUTE, "live");

        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "fresh").allowed()).isFalse();
        assertThat(limiter.check(TWO_PER_MINUTE, "fresh").allowed()).isTrue();
        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "live")).isEqualTo(RateLimitDecision.allowed(0));
        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "live").allowed()).isFalse();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire(TWO_PER_MINUTE, "fresh").allowed()).isTrue();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RateLimitPolicy("x", 0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy("x", 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy(" ", 1, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryFixedWindowRateLimiter(clock, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryAfterIsRoundedUpToAtLeastOneSecond() {
        assertThat(RateLimitDecision.rejected(Duration.ofMillis(1500)).retryAfterSeconds()).isEqualTo(2);
        assertThat(RateLimitDecision.rejected(Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
        assertThat(RateLimitDecision.rejected(Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-28T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
