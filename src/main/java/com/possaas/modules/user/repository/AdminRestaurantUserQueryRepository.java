package com.possaas.modules.user.repository;

import com.possaas.modules.user.dto.AdminRestaurantUserResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;

public interface AdminRestaurantUserQueryRepository {
    Page<AdminRestaurantUserResponse> search(UUID restaurantId, AdminRestaurantUserCriteria criteria);
    Optional<AdminRestaurantUserResponse> findDetail(UUID restaurantId, UUID userId);
}
