package com.possaas.modules.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionExpirationServiceClockTest {
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Mock RestaurantSubscriptionRepository subscriptionRepository;
    @Mock AuditService auditService;

    @Test
    void fixedClockMakesTheExpirationBoundaryDeterministic() {
        SubscriptionExpirationService service = new SubscriptionExpirationService(
            subscriptionRepository,
            auditService,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        RestaurantSubscription due = subscription(NOW);

        assertThat(service.expireIfDue(due)).isTrue();
        assertThat(due.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        verify(subscriptionRepository).flush();
        verify(auditService).record(
            eq(due.getRestaurantId()),
            eq(null),
            eq("SUBSCRIPTION_EXPIRED"),
            eq("restaurant_subscriptions"),
            eq(due.getId()),
            any(),
            any(),
            eq(null)
        );

        RestaurantSubscription notDue = subscription(NOW.plusNanos(1));
        assertThat(service.expireIfDue(notDue)).isFalse();
        assertThat(notDue.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(auditService, never()).record(
            eq(notDue.getRestaurantId()),
            eq(null),
            eq("SUBSCRIPTION_EXPIRED"),
            eq("restaurant_subscriptions"),
            eq(notDue.getId()),
            any(),
            any(),
            eq(null)
        );
    }

    private static RestaurantSubscription subscription(Instant endAt) {
        RestaurantSubscription subscription = new RestaurantSubscription();
        subscription.setId(UUID.randomUUID());
        subscription.setRestaurantId(UUID.randomUUID());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setEndAt(endAt);
        return subscription;
    }
}
