package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Shared guard for admin sub-resources of a restaurant (notes, usage, ...). */
@Component
@RequiredArgsConstructor
public class AdminRestaurantLookup {
    private final RestaurantRepository restaurants;

    public void requireExisting(UUID restaurantId) {
        restaurants.findByIdAndDeletedAtIsNull(restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
    }
}
