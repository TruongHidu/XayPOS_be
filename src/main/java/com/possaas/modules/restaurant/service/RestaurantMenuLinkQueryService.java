package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.modules.restaurant.dto.RestaurantMenuLinkResponse;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantMenuLinkQueryService {
    private final CurrentTenantProvider tenant;
    private final RestaurantRepository restaurants;

    public RestaurantMenuLinkResponse get() {
        var restaurant = restaurants.findByIdAndDeletedAtIsNull(tenant.getRequiredRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        if (restaurant.getPublicOrderToken() == null) throw notInitialized();
        return RestaurantMenuLinkResponse.fromToken(restaurant.getPublicOrderToken());
    }

    static ResourceNotFoundException notInitialized() {
        return new ResourceNotFoundException("PUBLIC_MENU_LINK_NOT_INITIALIZED", "Menu link has not been initialized");
    }
}
