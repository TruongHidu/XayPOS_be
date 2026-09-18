package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.restaurant.dto.AdminRestaurantDetailResponse;
import com.possaas.modules.restaurant.dto.AdminRestaurantSummaryResponse;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantAdminCriteria;
import com.possaas.modules.restaurant.repository.RestaurantAdminQueryRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RestaurantAdminQueryService {
    private final RestaurantAdminQueryRepository queryRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminRestaurantSummaryResponse> search(
        String query,
        RestaurantStatus status,
        int page,
        int size,
        String sortBy,
        String direction
    ) {
        RestaurantAdminCriteria criteria = RestaurantAdminCriteria.from(
            query,
            status,
            page,
            size,
            sortBy,
            direction
        );
        return PageResponse.from(queryRepository.search(criteria, clock.instant()));
    }

    @Transactional(readOnly = true)
    public AdminRestaurantDetailResponse findDetail(UUID restaurantId) {
        return queryRepository.findDetail(restaurantId, clock.instant())
            .orElseThrow(() -> new ResourceNotFoundException(
                "RESTAURANT_NOT_FOUND",
                "Restaurant not found"
            ));
    }
}
