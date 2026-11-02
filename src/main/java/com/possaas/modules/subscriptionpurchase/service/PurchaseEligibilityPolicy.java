package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PurchaseEligibilityPolicy {
    public String blockedReason(boolean accepting, CurrentUser actor, RestaurantSubscription subscription,
        SubscriptionPurchase open, Instant now) {
        if (!accepting) return "PURCHASE_UNAVAILABLE";
        if (!actor.hasPermission(PurchaseAccessPolicy.CREATE)) return "PURCHASE_PERMISSION_REQUIRED";
        if (subscription != null && subscription.getStatus() == SubscriptionStatus.ACTIVE && subscription.getEndAt().isAfter(now))
            return "SUBSCRIPTION_ALREADY_ACTIVE";
        if (subscription != null && subscription.getStatus() == SubscriptionStatus.PENDING) return "SUBSCRIPTION_PENDING_EXISTS";
        if (open != null && open.getStatus() == PurchaseStatus.REVIEW_REQUIRED) return "PURCHASE_REVIEW_REQUIRED";
        if (open != null && open.getStatus().isOpen() && !open.isDue(now)) return "PURCHASE_IN_PROGRESS";
        return null;
    }

    public void assertEligible(String reason) {
        if (reason != null) throw new BusinessException("PURCHASE_UNAVAILABLE".equals(reason) ? HttpStatus.SERVICE_UNAVAILABLE
            : "PURCHASE_PERMISSION_REQUIRED".equals(reason) ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT,
            reason, "Restaurant is not eligible for a new purchase");
    }
}
