package com.possaas.modules.audit.mapper;

import com.possaas.modules.audit.dto.AuditLogResponse;
import com.possaas.modules.audit.repository.AuditLogQueryRow;
import com.possaas.modules.audit.service.AuditDataRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditLogResponseMapper {
    private final AuditDataRedactor auditDataRedactor;

    public AuditLogResponse toResponse(AuditLogQueryRow row) {
        return new AuditLogResponse(
            row.id(),
            row.restaurantId(),
            row.restaurantCode(),
            row.restaurantName(),
            row.actorUserId(),
            row.actorName(),
            row.actorEmail(),
            row.actionCode(),
            row.entityType(),
            row.entityId(),
            auditDataRedactor.redact(row.beforeData()),
            auditDataRedactor.redact(row.afterData()),
            row.ipAddress() == null ? null : row.ipAddress().getHostAddress(),
            row.createdAt()
        );
    }
}
