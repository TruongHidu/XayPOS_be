package com.possaas.modules.restaurant.service;

import com.possaas.modules.restaurant.application.port.RestaurantAccessChecker;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatabaseRestaurantAccessChecker implements RestaurantAccessChecker {
    private final RestaurantRepository restaurantRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID restaurantId) {
        return restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
            .filter(restaurant -> restaurant.getStatus() == RestaurantStatus.ACTIVE)
            .isPresent();
    }
}
