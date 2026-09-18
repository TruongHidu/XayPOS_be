package com.possaas.modules.restaurant.dto;

import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminSubscriptionBriefResponse(
    UUID id,
    String packageCode,
    SubscriptionStatus status,
    Instant startAt,
    Instant endAt,
    boolean autoRenew
) {}
