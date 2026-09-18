package com.possaas.modules.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
    UUID id,
    UUID restaurantId,
    String restaurantCode,
    String restaurantName,
    UUID actorUserId,
    String actorName,
    String actorEmail,
    String actionCode,
    String entityType,
    UUID entityId,
    Map<String, Object> beforeData,
    Map<String, Object> afterData,
    String ipAddress,
    Instant createdAt
) {}
