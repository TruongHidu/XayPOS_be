package com.possaas.modules.subscription;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.possaas.modules.subscription.application.port.EffectiveEntitlementLookup;
import com.possaas.modules.subscription.domain.SubscriptionNotActiveException;
import com.possaas.modules.subscription.mapper.SubscriptionMapper;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.subscription.service.SubscriptionQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionNotActiveReasonTest {
    private final UUID restaurant = UUID.randomUUID();
    private final EffectiveEntitlementLookup lookup = mock(EffectiveEntitlementLookup.class);
    private final RestaurantSubscriptionRepository subscriptions = mock(RestaurantSubscriptionRepository.class);
    private final SubscriptionQueryService service = new SubscriptionQueryService(lookup, subscriptions,
        mock(PackagePlanRepository.class), mock(SubscriptionMapper.class),
        Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC));

    @Test void neverSubscribedAsksToSubscribe() {
        when(lookup.findForRestaurant(restaurant)).thenReturn(Optional.empty());
        when(subscriptions.hasLapsedSubscription(eq(restaurant), any())).thenReturn(false);
        assertThatThrownBy(() -> service.getCurrent(restaurant))
            .isInstanceOf(SubscriptionNotActiveException.class)
            .hasFieldOrPropertyWithValue("code", "SUBSCRIPTION_NOT_ACTIVE")
            .extracting(e -> ((SubscriptionNotActiveException) e).details())
            .isEqualTo(Map.of("reason", "NONE"));
    }

    @Test void aSubscriptionThatRanOutAsksToRenew() {
        when(lookup.findForRestaurant(restaurant)).thenReturn(Optional.empty());
        when(subscriptions.hasLapsedSubscription(eq(restaurant), any())).thenReturn(true);
        assertThatThrownBy(() -> service.getCurrent(restaurant))
            .isInstanceOf(SubscriptionNotActiveException.class)
            .extracting(e -> ((SubscriptionNotActiveException) e).details())
            .isEqualTo(Map.of("reason", "EXPIRED"));
    }
}
