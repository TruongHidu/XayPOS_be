package com.possaas.modules.audit.service;

import java.util.Map;
import java.util.UUID;

public record AuditRecordCommand(
    UUID restaurantId,
    UUID actorUserId,
    String actionCode,
    String entityType,
    UUID entityId,
    Map<String, Object> beforeData,
    Map<String, Object> afterData,
    String ipAddress
) {}
