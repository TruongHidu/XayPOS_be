package com.possaas.modules.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubscriptionValidityPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    private SubscriptionValidityPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SubscriptionValidityPolicy(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startAtIsInclusive() {
        assertThat(policy.isEffective(activeSubscription(NOW, NOW.plusSeconds(60)))).isTrue();
    }

    @Test
    void endAtIsExclusive() {
        assertThat(policy.isEffective(activeSubscription(NOW.minusSeconds(60), NOW))).isFalse();
    }

    @Test
    void activeSubscriptionBeforeItsStartIsNotEffective() {
        assertThat(policy.isEffective(activeSubscription(NOW.plusSeconds(1), NOW.plusSeconds(60)))).isFalse();
    }

    @Test
    void activeSubscriptionAfterItsEndIsNotEffective() {
        assertThat(policy.isEffective(activeSubscription(NOW.minusSeconds(60), NOW.minusSeconds(1)))).isFalse();
    }

    @Test
    void pendingSubscriptionIsNotEffective() {
        RestaurantSubscription subscription = activeSubscription(NOW.minusSeconds(60), NOW.plusSeconds(60));
        subscription.setStatus(SubscriptionStatus.PENDING);

        assertThat(policy.isEffective(subscription)).isFalse();
    }

    @Test
    void cancelledSubscriptionIsNotEffective() {
        RestaurantSubscription subscription = activeSubscription(NOW.minusSeconds(60), NOW.plusSeconds(60));
        subscription.setStatus(SubscriptionStatus.CANCELLED);

        assertThat(policy.isEffective(subscription)).isFalse();
    }

    private static RestaurantSubscription activeSubscription(Instant startAt, Instant endAt) {
        RestaurantSubscription subscription = new RestaurantSubscription();
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartAt(startAt);
        subscription.setEndAt(endAt);
        return subscription;
    }
}
