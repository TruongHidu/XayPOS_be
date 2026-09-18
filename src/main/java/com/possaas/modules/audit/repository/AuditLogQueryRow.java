package com.possaas.modules.audit.repository;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogQueryRow(
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
    InetAddress ipAddress,
    Instant createdAt
) {}
