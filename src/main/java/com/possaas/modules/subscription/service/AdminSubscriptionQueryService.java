package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.dto.AdminSubscriptionDetailResponse;
import com.possaas.modules.subscription.dto.AdminSubscriptionSummaryResponse;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.mapper.AdminSubscriptionMapper;
import com.possaas.modules.subscription.repository.AdminSubscriptionCriteria;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSubscriptionQueryService {
    private static final Set<String> SORT_FIELDS = Set.of(
        "createdAt", "startAt", "endAt", "status", "priceAmount"
    );

    private final RestaurantSubscriptionRepository subscriptionRepository;
    private final RestaurantRepository restaurantRepository;
    private final PackagePlanRepository packageRepository;
    private final AdminSubscriptionMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminSubscriptionSummaryResponse> search(
        AdminSubscriptionCriteria criteria,
        int page,
        int size,
        String sortBy,
        String direction
    ) {
        PageRequest pageable = pageRequest(page, size, sortBy, direction);
        Instant now = clock.instant();
        Page<RestaurantSubscription> subscriptions = subscriptionRepository.search(
            normalize(criteria),
            now,
            pageable
        );
        Map<UUID, Restaurant> restaurants = restaurantRepository.findAllById(
            subscriptions.getContent().stream().map(RestaurantSubscription::getRestaurantId).distinct().toList()
        ).stream().collect(Collectors.toMap(Restaurant::getId, Function.identity()));
        Map<UUID, PackagePlan> packages = packageRepository.findAllById(
            subscriptions.getContent().stream().map(RestaurantSubscription::getPackageId).distinct().toList()
        ).stream().collect(Collectors.toMap(PackagePlan::getId, Function.identity()));

        var content = subscriptions.getContent().stream().map(subscription -> mapper.toSummary(
            subscription,
            requiredRestaurant(restaurants, subscription.getRestaurantId()),
            requiredPackage(packages, subscription.getPackageId()),
            now
        )).toList();
        return new PageResponse<>(
            content,
            subscriptions.getNumber(),
            subscriptions.getSize(),
            subscriptions.getTotalElements(),
            subscriptions.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminSubscriptionSummaryResponse> findByRestaurant(
        UUID restaurantId,
        String packageCode,
        com.possaas.modules.subscription.entity.SubscriptionStatus status,
        int page,
        int size
    ) {
        requireRestaurant(restaurantId);
        return search(
            new AdminSubscriptionCriteria(null, restaurantId, packageCode, status, null),
            page,
            size,
            "createdAt",
            "desc"
        );
    }

    @Transactional(readOnly = true)
    public AdminSubscriptionDetailResponse findDetail(UUID restaurantId, UUID subscriptionId) {
        Restaurant restaurant = requireRestaurant(restaurantId);
        RestaurantSubscription subscription = subscriptionRepository
            .findByIdAndRestaurantId(subscriptionId, restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "SUBSCRIPTION_NOT_FOUND",
                "Subscription not found"
            ));
        PackagePlan packagePlan = packageRepository.findById(subscription.getPackageId())
            .orElseThrow(() -> new ResourceNotFoundException("PACKAGE_NOT_FOUND", "Package not found"));
        return mapper.toDetail(subscription, restaurant, packagePlan, clock.instant());
    }

    private Restaurant requireRestaurant(UUID restaurantId) {
        return restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
    }

    private static Restaurant requiredRestaurant(Map<UUID, Restaurant> restaurants, UUID id) {
        Restaurant restaurant = restaurants.get(id);
        if (restaurant == null || restaurant.getDeletedAt() != null) {
            throw new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found");
        }
        return restaurant;
    }

    private static PackagePlan requiredPackage(Map<UUID, PackagePlan> packages, UUID id) {
        PackagePlan packagePlan = packages.get(id);
        if (packagePlan == null) {
            throw new ResourceNotFoundException("PACKAGE_NOT_FOUND", "Package not found");
        }
        return packagePlan;
    }

    private static AdminSubscriptionCriteria normalize(AdminSubscriptionCriteria criteria) {
        return new AdminSubscriptionCriteria(
            trimToNull(criteria.query()),
            criteria.restaurantId(),
            uppercaseToNull(criteria.packageCode()),
            criteria.status(),
            criteria.effective()
        );
    }

    private static PageRequest pageRequest(
        int page,
        int size,
        String sortBy,
        String direction
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw validation("Page must be non-negative and size must be between 1 and 100");
        }
        String normalizedSort = trimToNull(sortBy);
        if (normalizedSort == null || !SORT_FIELDS.contains(normalizedSort)) {
            throw validation("Unsupported subscription sort field");
        }
        Sort.Direction sortDirection;
        try {
            sortDirection = Sort.Direction.fromString(direction == null ? "desc" : direction.trim());
        } catch (IllegalArgumentException exception) {
            throw validation("Sort direction must be asc or desc");
        }
        return PageRequest.of(
            page,
            size,
            Sort.by(
                new Sort.Order(sortDirection, normalizedSort),
                new Sort.Order(sortDirection, "id")
            )
        );
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String uppercaseToNull(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static BusinessException validation(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
