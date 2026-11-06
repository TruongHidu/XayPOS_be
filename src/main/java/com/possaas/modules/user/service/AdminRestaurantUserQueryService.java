package com.possaas.modules.user.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.user.dto.AdminRestaurantUserResponse;
import com.possaas.modules.user.repository.AdminRestaurantUserCriteria;
import com.possaas.modules.user.repository.AdminRestaurantUserQueryRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRestaurantUserQueryService {
    private final RestaurantRepository restaurantRepository;
    private final AdminRestaurantUserQueryRepository queryRepository;

    public PageResponse<AdminRestaurantUserResponse> search(UUID restaurantId, String q, String roleCode,
            Boolean active, int page, int size, String sortBy, String direction) {
        requireRestaurant(restaurantId);
        return PageResponse.from(queryRepository.search(restaurantId,
            AdminRestaurantUserCriteria.from(q, roleCode, active, page, size, sortBy, direction)));
    }

    public AdminRestaurantUserResponse findDetail(UUID restaurantId, UUID userId) {
        requireRestaurant(restaurantId);
        return queryRepository.findDetail(restaurantId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));
    }

    private void requireRestaurant(UUID restaurantId) {
        restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
    }
}
