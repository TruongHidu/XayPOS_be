package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.*;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.dto.*;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantMenuLinkCommandService {
    private final CurrentTenantProvider tenant;
    private final CurrentUserProvider users;
    private final RestaurantRepository restaurants;
    private final PublicLinkTokenGenerator tokens;
    private final AuditService audit;

    public RestaurantMenuLinkResponse initialize(String ip) {
        var restaurant = lockedRestaurant();
        if (restaurant.getPublicOrderToken() != null) return RestaurantMenuLinkResponse.fromToken(restaurant.getPublicOrderToken());
        restaurant.setPublicOrderToken(freshToken(null));
        restaurants.saveAndFlush(restaurant);
        record(restaurant, "RESTAURANT_MENU_LINK_INITIALIZED", Map.of("initialized", false), Map.of("initialized", true), ip);
        return RestaurantMenuLinkResponse.fromToken(restaurant.getPublicOrderToken());
    }

    public RestaurantMenuLinkResponse rotate(RotateRestaurantMenuLinkRequest request, String ip) {
        var restaurant = lockedRestaurant();
        var previous = restaurant.getPublicOrderToken();
        if (previous == null) throw RestaurantMenuLinkQueryService.notInitialized();
        if (!Objects.equals(previous, request.expectedToken()))
            throw new ConflictException("CONCURRENT_MENU_LINK_UPDATE", "Menu link changed; reload before rotating");
        restaurant.setPublicOrderToken(freshToken(previous));
        restaurants.saveAndFlush(restaurant);
        record(restaurant, "RESTAURANT_MENU_LINK_ROTATED", Map.of("initialized", true),
                Map.of("initialized", true, "rotated", true), ip);
        return RestaurantMenuLinkResponse.fromToken(restaurant.getPublicOrderToken());
    }

    private Restaurant lockedRestaurant() {
        return restaurants.findByIdForUpdate(tenant.getRequiredRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
    }

    private String freshToken(String previous) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String candidate = tokens.generate();
            if (PublicLinkTokenGenerator.isValid(candidate) && !Objects.equals(candidate, previous)) return candidate;
        }
        throw new ConflictException("PUBLIC_MENU_TOKEN_CONFLICT", "Could not allocate a new menu token; retry");
    }

    private void record(Restaurant restaurant, String action, Map<String, Object> before, Map<String, Object> after, String ip) {
        audit.record(restaurant.getId(), users.getRequired().userId(), action, "restaurants", restaurant.getId(), before, after, ip);
    }
}
