package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.subscription.application.port.SubscriptionPurchaseConflictGuard;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class DatabaseSubscriptionPurchaseConflictGuard implements SubscriptionPurchaseConflictGuard {
    private final SubscriptionPurchaseRepository purchases;
    private final PurchaseExpirationService expiration;
    private final RestaurantRepository restaurants;

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void assertNoOpenPurchase(UUID restaurantId, Instant now) {
        purchases.findOpenForUpdate(restaurantId).ifPresent(p -> {
            if (!expiration.expireIfDue(p, now))
                throw new BusinessException(HttpStatus.CONFLICT, "SUBSCRIPTION_PURCHASE_IN_PROGRESS", "Resolve the open package purchase before assigning a subscription");
        });
        // SELECT FOR UPDATE alone does not refresh a waiting REPEATABLE_READ transaction's snapshot.
        // Touching the locked row commits a new MVCC version, so it retries and sees the admin subscription.
        // The existing restaurants updated_at trigger records this successful subscription coordination.
        restaurants.touchSubscriptionCoordination(restaurantId);
    }
}
