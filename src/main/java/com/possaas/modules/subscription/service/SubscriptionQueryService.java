package com.possaas.modules.subscription.service;

import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import com.possaas.modules.subscription.dto.CurrentEntitlementResponse;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscription.dto.SubscriptionResponse;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.mapper.SubscriptionMapper;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionQueryService {
    private final CurrentEntitlementQuery currentEntitlementQuery;
    private final RestaurantSubscriptionRepository subscriptionRepository;
    private final PackagePlanRepository packageRepository;
    private final SubscriptionMapper subscriptionMapper;

    @Transactional(readOnly = true)
    public CurrentEntitlementResponse getCurrent(UUID restaurantId) {
        CurrentEntitlement entitlement = currentEntitlementQuery.getForRestaurant(restaurantId);
        return subscriptionMapper.toResponse(entitlement);
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionResponse> getHistory(UUID restaurantId, Pageable pageable) {
        Page<RestaurantSubscription> subscriptions = subscriptionRepository
            .findAllByRestaurantIdOrderByCreatedAtDesc(restaurantId, pageable);
        Map<UUID, PackagePlan> packages = packageRepository
            .findAllById(subscriptions.getContent().stream().map(RestaurantSubscription::getPackageId).toList())
            .stream()
            .collect(Collectors.toMap(PackagePlan::getId, Function.identity()));
        Page<SubscriptionResponse> responses = subscriptions.map(subscription -> {
            PackagePlan packagePlan = packages.get(subscription.getPackageId());
            String packageCode = packagePlan == null ? null : packagePlan.getCode();
            return subscriptionMapper.toResponse(subscription, packageCode);
        });
        return PageResponse.from(responses);
    }
}
