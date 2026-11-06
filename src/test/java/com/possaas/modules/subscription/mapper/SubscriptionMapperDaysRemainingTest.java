package com.possaas.modules.subscription.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubscriptionMapperDaysRemainingTest {
    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");

    @Test
    void roundsPartialDaysUp() {
        assertThat(SubscriptionMapper.daysRemaining(NOW.plus(Duration.ofDays(30)), NOW)).isEqualTo(30L);
        assertThat(SubscriptionMapper.daysRemaining(NOW.plus(Duration.ofDays(30)).minusSeconds(1), NOW)).isEqualTo(30L);
        assertThat(SubscriptionMapper.daysRemaining(NOW.plus(Duration.ofHours(23)), NOW)).isEqualTo(1L);
        assertThat(SubscriptionMapper.daysRemaining(NOW.plusSeconds(1), NOW)).isEqualTo(1L);
    }

    @Test
    void isZeroOnceExpiredAndNullWithoutEnd() {
        assertThat(SubscriptionMapper.daysRemaining(NOW, NOW)).isZero();
        assertThat(SubscriptionMapper.daysRemaining(NOW.minus(Duration.ofDays(2)), NOW)).isZero();
        assertThat(SubscriptionMapper.daysRemaining(null, NOW)).isNull();
    }
}
