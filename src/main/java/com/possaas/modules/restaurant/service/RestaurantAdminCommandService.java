package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.application.port.RestaurantSessionRevoker;
import com.possaas.modules.restaurant.dto.AdminRestaurantDetailResponse;
import com.possaas.modules.restaurant.dto.UpdateRestaurantStatusRequest;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantAdminQueryRepository;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RestaurantAdminCommandService {
    private final RestaurantRepository restaurantRepository;
    private final RestaurantAdminQueryRepository queryRepository;
    private final RestaurantSessionRevoker sessionRevoker;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional
    public AdminRestaurantDetailResponse updateStatus(
        UUID restaurantId,
        UpdateRestaurantStatusRequest request,
        UUID actorUserId,
        String ipAddress
    ) {
        Restaurant restaurant = restaurantRepository.findByIdForUpdate(restaurantId)
            .orElseThrow(RestaurantAdminCommandService::restaurantNotFound);
        RestaurantStatus previousStatus = restaurant.getStatus();
        if (previousStatus == request.status()) {
            return detail(restaurantId);
        }

        String reason = request.reason().trim();
        restaurant.setStatus(request.status());
        restaurantRepository.saveAndFlush(restaurant);

        if (previousStatus == RestaurantStatus.ACTIVE
            && request.status() != RestaurantStatus.ACTIVE) {
            sessionRevoker.revokeAllActiveForRestaurant(restaurantId);
        }

        auditService.record(
            restaurantId,
            actorUserId,
            "RESTAURANT_STATUS_CHANGED",
            "restaurants",
            restaurantId,
            Map.of("status", previousStatus.name()),
            Map.of("status", request.status().name(), "reason", reason),
            ipAddress
        );
        return detail(restaurantId);
    }

    private AdminRestaurantDetailResponse detail(UUID restaurantId) {
        return queryRepository.findDetail(restaurantId, clock.instant())
            .orElseThrow(RestaurantAdminCommandService::restaurantNotFound);
    }

    private static ResourceNotFoundException restaurantNotFound() {
        return new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found");
    }
}
