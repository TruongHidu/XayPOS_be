package com.possaas.modules.subscription.repository;

import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.util.UUID;

public record AdminSubscriptionCriteria(
    String query,
    UUID restaurantId,
    String packageCode,
    SubscriptionStatus status,
    Boolean effective
) {}
