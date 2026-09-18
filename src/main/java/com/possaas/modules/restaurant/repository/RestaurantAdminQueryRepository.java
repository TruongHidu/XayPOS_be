package com.possaas.modules.restaurant.repository;

import com.possaas.modules.restaurant.dto.AdminRestaurantDetailResponse;
import com.possaas.modules.restaurant.dto.AdminRestaurantSummaryResponse;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;

public interface RestaurantAdminQueryRepository {
    Page<AdminRestaurantSummaryResponse> search(RestaurantAdminCriteria criteria, Instant now);

    Optional<AdminRestaurantDetailResponse> findDetail(java.util.UUID restaurantId, Instant now);
}
