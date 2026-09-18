package com.possaas.modules.subscription.repository;

import com.possaas.modules.subscription.entity.RestaurantSubscription;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminSubscriptionQueryRepository {
    Page<RestaurantSubscription> search(
        AdminSubscriptionCriteria criteria,
        Instant now,
        Pageable pageable
    );
}
