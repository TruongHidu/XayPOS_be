package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.modules.restaurant.dto.RestaurantProfileResponse;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RestaurantProfileQueryService {
    private final CurrentTenantProvider tenant;
    private final RestaurantRepository restaurants;

    @Transactional(readOnly = true)
    public RestaurantProfileResponse get() {
        return restaurants.findByIdAndDeletedAtIsNull(tenant.getRequiredRestaurantId())
            .map(RestaurantProfileResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
    }
}
