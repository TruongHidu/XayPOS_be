package com.possaas.modules.subscription.domain;

import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SubscriptionValidityPolicy {
    private final Clock clock;

    public boolean isEffective(RestaurantSubscription subscription) {
        return isEffective(
            subscription.getStatus(),
            subscription.getStartAt(),
            subscription.getEndAt(),
            clock.instant()
        );
    }

    public boolean isEffective(SubscriptionStatus status, Instant startAt, Instant endAt, Instant now) {
        return status == SubscriptionStatus.ACTIVE
            && !now.isBefore(startAt)
            && now.isBefore(endAt);
    }
}
