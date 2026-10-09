package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.PublicLinkTokenGenerator;
import com.possaas.modules.restaurant.dto.PublicRestaurantMenuContext;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicRestaurantMenuResolver {
    private final RestaurantRepository restaurants;

    public PublicRestaurantMenuContext resolve(String token) {
        if (!PublicLinkTokenGenerator.isValid(token)) throw unavailable();
        var restaurant = restaurants.findByPublicOrderToken(token)
                .filter(r -> r.getDeletedAt() == null && r.getStatus() == RestaurantStatus.ACTIVE)
                .orElseThrow(PublicRestaurantMenuResolver::unavailable);
        return new PublicRestaurantMenuContext(restaurant.getId(), restaurant.getName(), restaurant.getCurrencyCode());
    }

    private static ResourceNotFoundException unavailable() {
        return new ResourceNotFoundException("PUBLIC_MENU_NOT_FOUND", "Menu không còn khả dụng.");
    }
}
