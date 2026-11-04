package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.subscriptionpurchase.dto.PaymentAttemptResponses;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentAttemptRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only restore of the latest attempt; expiry is projected, never written. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class PaymentAttemptQueryService {
    private final PurchaseAccessPolicy access;
    private final SubscriptionPurchaseRepository purchases;
    private final SubscriptionPaymentAttemptRepository attempts;
    private final PaymentAttemptResponseMapper mapper;
    private final Clock clock;

    public PaymentAttemptResponses.Current current(UUID purchaseId) {
        CurrentUser actor = access.require(PurchaseAccessPolicy.VIEW);
        if (purchases.findTermsByIdAndRestaurantId(purchaseId, actor.restaurantId()).isEmpty())
            throw new ResourceNotFoundException("PURCHASE_NOT_FOUND", "Purchase not found");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var latest = attempts.findFirstByRestaurantIdAndPurchaseIdOrderByCreatedAtDescIdDesc(actor.restaurantId(), purchaseId)
            .map(a -> mapper.view(a, now)).orElse(null);
        return new PaymentAttemptResponses.Current(latest, now);
    }
}
