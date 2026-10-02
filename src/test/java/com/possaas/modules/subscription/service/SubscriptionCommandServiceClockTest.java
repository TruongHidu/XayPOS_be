package com.possaas.modules.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionValidityPolicy;
import com.possaas.modules.subscription.dto.CreateSubscriptionRequest;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.mapper.SubscriptionMapper;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionCommandServiceClockTest {
    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID restaurantId = UUID.randomUUID();
    @Mock RestaurantRepository restaurants;
    @Mock PackagePlanRepository packages;
    @Mock RestaurantSubscriptionRepository subscriptions;
    @Mock FeatureSnapshotFactory snapshots;
    @Mock AuditService audits;
    private SubscriptionCommandService commands;

    @BeforeEach
    void setUp() {
        SubscriptionExpirationService expiration = new SubscriptionExpirationService(restaurants, subscriptions, audits, clock);
        commands = new SubscriptionCommandService(restaurants, packages, subscriptions, snapshots,
            new SubscriptionValidityPolicy(clock), new SubscriptionMapper(), expiration, audits, clock);
        when(restaurants.findByIdForUpdate(restaurantId)).thenReturn(Optional.of(new Restaurant()));
    }

    @Test
    void activationRejectsExactEndBoundaryBeforeWritingSnapshotOrAudit() {
        RestaurantSubscription pending = subscription(SubscriptionStatus.PENDING);
        when(subscriptions.findByIdAndRestaurantId(pending.getId(), restaurantId)).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> commands.activate(restaurantId, pending.getId(), null, null))
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("SUBSCRIPTION_PERIOD_EXPIRED"));
        verify(subscriptions, never()).saveAndFlush(any());
        verifyNoInteractions(snapshots, audits);
    }

    @Test
    void creationReconcilesActiveAtExactEndBoundaryBeforeSavingPending() {
        RestaurantSubscription active = subscription(SubscriptionStatus.ACTIVE);
        when(subscriptions.findActiveForUpdate(restaurantId)).thenReturn(Optional.of(active));
        PackagePlan plan = new PackagePlan();
        plan.setId(UUID.randomUUID());
        plan.setCode("BASIC");
        plan.setActive(true);
        when(packages.findByCodeForUpdate("BASIC")).thenReturn(Optional.of(plan));
        when(subscriptions.saveAndFlush(any())).thenAnswer(invocation -> {
            assertThat(active.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
            RestaurantSubscription saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        var created = commands.create(restaurantId, new CreateSubscriptionRequest(
            "BASIC", NOW, NOW.plusSeconds(60), false, BigDecimal.ZERO, "VND"), null, null);
        assertThat(created.status()).isEqualTo(SubscriptionStatus.PENDING);
        var order = inOrder(restaurants, subscriptions, audits);
        order.verify(restaurants).findByIdForUpdate(restaurantId);
        order.verify(subscriptions).findActiveForUpdate(restaurantId);
        order.verify(subscriptions).flush();
        order.verify(audits).record(eq(restaurantId), isNull(), eq("SUBSCRIPTION_EXPIRED"),
            eq("restaurant_subscriptions"), eq(active.getId()), any(), any(), isNull());
        order.verify(subscriptions).findByRestaurantIdAndStatus(restaurantId, SubscriptionStatus.PENDING);
        order.verify(subscriptions).saveAndFlush(any());
    }

    private RestaurantSubscription subscription(SubscriptionStatus status) {
        RestaurantSubscription subscription = new RestaurantSubscription();
        subscription.setId(UUID.randomUUID());
        subscription.setRestaurantId(restaurantId);
        subscription.setStatus(status);
        subscription.setStartAt(NOW.minusSeconds(60));
        subscription.setEndAt(NOW);
        return subscription;
    }
}
