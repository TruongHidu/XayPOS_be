package com.possaas.modules.subscription.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscription.application.port.PurchasedSubscriptionActivation;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.domain.SubscriptionPeriodCalculator;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The purchase being activated is itself the open purchase, so the admin purchase guard is intentionally not used. */
@Service
@RequiredArgsConstructor
public class PurchasedSubscriptionActivationService implements PurchasedSubscriptionActivation {
    private final RestaurantSubscriptionRepository subscriptions;
    private final SubscriptionExpirationService expiration;
    private final AuditService audits;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ActivationBlockedException.class)
    public Result activate(Command command) {
        var active = subscriptions.findActiveForUpdate(command.restaurantId()).orElse(null);
        if (active != null && !expiration.expireIfDue(active)) throw new ActivationBlockedException("SUBSCRIPTION_CONFLICT");
        if (subscriptions.findByRestaurantIdAndStatus(command.restaurantId(), SubscriptionStatus.PENDING).isPresent())
            throw new ActivationBlockedException("SUBSCRIPTION_CONFLICT");

        var startAt = command.activatedAt();
        var endAt = SubscriptionPeriodCalculator.endAt(startAt, command.billingZoneId(), command.billingCycleMonths());
        var snapshot = new SubscriptionFeatureSnapshot(2, command.packageCode(), command.features(), startAt, command.maxStaff());
        RestaurantSubscription subscription = new RestaurantSubscription();
        subscription.setRestaurantId(command.restaurantId());
        subscription.setPackageId(command.packageId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartAt(startAt);
        subscription.setEndAt(endAt);
        subscription.setAutoRenew(false);
        subscription.setPriceAmount(command.priceAmount());
        subscription.setCurrencyCode(command.currencyCode());
        subscription.setFeatureSnapshot(snapshot.toMap());
        subscription.setActivatedAt(startAt);
        subscription = subscriptions.saveAndFlush(subscription);

        audits.record(command.restaurantId(), null, "SUBSCRIPTION_ACTIVATED", "restaurant_subscriptions", subscription.getId(), null,
            Map.of("status", SubscriptionStatus.ACTIVE.name(), "packageCode", command.packageCode(),
                "startAt", startAt.toString(), "endAt", endAt.toString(), "featureCount", snapshot.features().size(),
                "purchaseId", command.purchaseId().toString()), null);
        return new Result(subscription.getId(), startAt, endAt);
    }
}
