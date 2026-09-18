package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.domain.SubscriptionValidityPolicy;
import com.possaas.modules.subscription.dto.ChangePackageRequest;
import com.possaas.modules.subscription.dto.CreateSubscriptionRequest;
import com.possaas.modules.subscription.dto.SubscriptionResponse;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.mapper.SubscriptionMapper;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionCommandService {
    private final RestaurantRepository restaurantRepository;
    private final PackagePlanRepository packageRepository;
    private final RestaurantSubscriptionRepository subscriptionRepository;
    private final FeatureSnapshotFactory snapshotFactory;
    private final SubscriptionValidityPolicy validityPolicy;
    private final SubscriptionMapper subscriptionMapper;
    private final SubscriptionExpirationService expirationService;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional
    public SubscriptionResponse create(
        UUID restaurantId,
        CreateSubscriptionRequest request,
        UUID actorUserId,
        String ipAddress
    ) {
        lockRestaurant(restaurantId);
        validatePeriod(request.startAt(), request.endAt());
        PackagePlan packagePlan = requireActivePackage(request.packageCode());

        RestaurantSubscription subscription = new RestaurantSubscription();
        subscription.setRestaurantId(restaurantId);
        subscription.setPackageId(packagePlan.getId());
        subscription.setStatus(SubscriptionStatus.PENDING);
        subscription.setStartAt(request.startAt());
        subscription.setEndAt(request.endAt());
        subscription.setAutoRenew(request.autoRenew());
        subscription.setPriceAmount(request.priceAmount());
        subscription.setCurrencyCode(normalizeCurrency(request.currencyCode()));
        subscription = subscriptionRepository.saveAndFlush(subscription);

        auditService.record(
            restaurantId,
            actorUserId,
            "SUBSCRIPTION_CREATED",
            "restaurant_subscriptions",
            subscription.getId(),
            null,
            Map.of(
                "packageCode", packagePlan.getCode(),
                "status", subscription.getStatus().name(),
                "startAt", subscription.getStartAt().toString(),
                "endAt", subscription.getEndAt().toString()
            ),
            ipAddress
        );
        return subscriptionMapper.toResponse(subscription, packagePlan.getCode());
    }

    @Transactional
    public SubscriptionResponse activate(
        UUID restaurantId,
        UUID subscriptionId,
        UUID actorUserId,
        String ipAddress
    ) {
        lockRestaurant(restaurantId);
        RestaurantSubscription subscription = requireSubscription(restaurantId, subscriptionId);
        if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
            throw conflict("SUBSCRIPTION_ALREADY_ACTIVE", "Subscription is already active");
        }
        if (subscription.getStatus() != SubscriptionStatus.PENDING) {
            throw conflict("INVALID_SUBSCRIPTION_TRANSITION", "Only a pending subscription can be activated");
        }
        subscriptionRepository.findActiveForUpdate(restaurantId).ifPresent(active -> {
            if (!expirationService.expireIfDue(active)) {
                throw conflict("SUBSCRIPTION_OVERLAP", "Restaurant already has an active subscription");
            }
        });

        PackagePlan packagePlan = requireActivePackage(subscription.getPackageId());
        SubscriptionFeatureSnapshot snapshot = snapshotFactory.capture(packagePlan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setActivatedAt(clock.instant());
        subscription.setFeatureSnapshot(snapshot.toMap());
        subscription = saveWithConcurrencyHandling(subscription);

        auditService.record(
            restaurantId,
            actorUserId,
            "SUBSCRIPTION_ACTIVATED",
            "restaurant_subscriptions",
            subscription.getId(),
            Map.of("status", SubscriptionStatus.PENDING.name()),
            Map.of(
                "status", SubscriptionStatus.ACTIVE.name(),
                "packageCode", packagePlan.getCode(),
                "featureCount", snapshot.features().size()
            ),
            ipAddress
        );
        return subscriptionMapper.toResponse(subscription, packagePlan.getCode());
    }

    @Transactional
    public SubscriptionResponse changePackage(
        UUID restaurantId,
        UUID subscriptionId,
        ChangePackageRequest request,
        UUID actorUserId,
        String ipAddress
    ) {
        expirationService.reconcileByIdIfDue(restaurantId, subscriptionId);
        lockRestaurant(restaurantId);
        RestaurantSubscription current = subscriptionRepository.findActiveForUpdate(restaurantId)
            .filter(subscription -> subscription.getId().equals(subscriptionId))
            .orElseThrow(() -> new BusinessException(
                HttpStatus.CONFLICT,
                "SUBSCRIPTION_NOT_ACTIVE",
                "The selected subscription is not active"
            ));
        if (!validityPolicy.isEffective(current)) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "SUBSCRIPTION_NOT_ACTIVE",
                "The selected subscription is not currently effective"
            );
        }

        Instant now = clock.instant();
        validatePeriod(now, request.endAt());
        PackagePlan oldPackage = requirePackage(current.getPackageId());
        PackagePlan newPackage = requireActivePackage(request.packageCode());
        SubscriptionFeatureSnapshot snapshot = snapshotFactory.capture(newPackage);

        current.setStatus(SubscriptionStatus.CANCELLED);
        current.setCancelledAt(now);
        subscriptionRepository.saveAndFlush(current);

        RestaurantSubscription replacement = new RestaurantSubscription();
        replacement.setRestaurantId(restaurantId);
        replacement.setPackageId(newPackage.getId());
        replacement.setStatus(SubscriptionStatus.ACTIVE);
        replacement.setStartAt(now);
        replacement.setEndAt(request.endAt());
        replacement.setAutoRenew(request.autoRenew());
        replacement.setPriceAmount(request.priceAmount());
        replacement.setCurrencyCode(normalizeCurrency(request.currencyCode()));
        replacement.setActivatedAt(now);
        replacement.setFeatureSnapshot(snapshot.toMap());
        replacement = saveWithConcurrencyHandling(replacement);

        auditService.record(
            restaurantId,
            actorUserId,
            "SUBSCRIPTION_PACKAGE_CHANGED",
            "restaurant_subscriptions",
            replacement.getId(),
            Map.of(
                "subscriptionId", current.getId().toString(),
                "packageCode", oldPackage.getCode()
            ),
            Map.of(
                "subscriptionId", replacement.getId().toString(),
                "packageCode", newPackage.getCode()
            ),
            ipAddress
        );
        return subscriptionMapper.toResponse(replacement, newPackage.getCode());
    }

    @Transactional
    public SubscriptionResponse cancel(
        UUID restaurantId,
        UUID subscriptionId,
        UUID actorUserId,
        String ipAddress
    ) {
        expirationService.reconcileByIdIfDue(restaurantId, subscriptionId);
        lockRestaurant(restaurantId);
        RestaurantSubscription subscription = requireSubscription(restaurantId, subscriptionId);
        PackagePlan packagePlan = requirePackage(subscription.getPackageId());
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            return subscriptionMapper.toResponse(subscription, packagePlan.getCode());
        }
        if (subscription.getStatus() == SubscriptionStatus.EXPIRED) {
            throw conflict("INVALID_SUBSCRIPTION_TRANSITION", "An expired subscription cannot be cancelled");
        }

        SubscriptionStatus previousStatus = subscription.getStatus();
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(clock.instant());
        subscription = saveWithConcurrencyHandling(subscription);
        auditService.record(
            restaurantId,
            actorUserId,
            "SUBSCRIPTION_CANCELLED",
            "restaurant_subscriptions",
            subscription.getId(),
            Map.of("status", previousStatus.name()),
            Map.of("status", SubscriptionStatus.CANCELLED.name()),
            ipAddress
        );
        return subscriptionMapper.toResponse(subscription, packagePlan.getCode());
    }

    private RestaurantSubscription saveWithConcurrencyHandling(RestaurantSubscription subscription) {
        try {
            return subscriptionRepository.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "CONCURRENT_SUBSCRIPTION_UPDATE",
                "Subscription was changed concurrently"
            );
        }
    }

    private void lockRestaurant(UUID restaurantId) {
        restaurantRepository.findByIdForUpdate(restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
    }

    private RestaurantSubscription requireSubscription(UUID restaurantId, UUID subscriptionId) {
        return subscriptionRepository.findByIdAndRestaurantId(subscriptionId, restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "SUBSCRIPTION_NOT_FOUND",
                "Subscription not found"
            ));
    }

    private PackagePlan requireActivePackage(String packageCode) {
        PackagePlan packagePlan = packageRepository.findByCode(normalizePackageCode(packageCode))
            .orElseThrow(() -> new ResourceNotFoundException("PACKAGE_NOT_FOUND", "Package not found"));
        if (!packagePlan.isActive()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PACKAGE_INACTIVE", "Package is inactive");
        }
        return packagePlan;
    }

    private PackagePlan requireActivePackage(UUID packageId) {
        PackagePlan packagePlan = requirePackage(packageId);
        if (!packagePlan.isActive()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PACKAGE_INACTIVE", "Package is inactive");
        }
        return packagePlan;
    }

    private PackagePlan requirePackage(UUID packageId) {
        return packageRepository.findById(packageId)
            .orElseThrow(() -> new ResourceNotFoundException("PACKAGE_NOT_FOUND", "Package not found"));
    }

    private static void validatePeriod(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SUBSCRIPTION_PERIOD",
                "Subscription endAt must be after startAt"
            );
        }
    }

    private static String normalizePackageCode(String packageCode) {
        return packageCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeCurrency(String currencyCode) {
        return currencyCode.trim().toUpperCase(Locale.ROOT);
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
