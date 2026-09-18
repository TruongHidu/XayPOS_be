package com.possaas.modules.audit.service;

import com.possaas.modules.audit.entity.AuditLog;
import com.possaas.modules.audit.repository.AuditLogRepository;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repository;
    private final AuditDataRedactor auditDataRedactor;

    @Transactional
    public void record(UUID restaurantId, UUID actorUserId, String actionCode, String entityType,
                       UUID entityId, Map<String, Object> beforeData, Map<String, Object> afterData, String ip) {
        repository.save(toEntity(new AuditRecordCommand(
            restaurantId,
            actorUserId,
            actionCode,
            entityType,
            entityId,
            beforeData,
            afterData,
            ip
        )));
    }

    @Transactional
    public void recordAll(List<AuditRecordCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }
        repository.saveAll(commands.stream().map(this::toEntity).toList());
    }

    private AuditLog toEntity(AuditRecordCommand command) {
        AuditLog log = new AuditLog();
        log.setRestaurantId(command.restaurantId());
        log.setActorUserId(command.actorUserId());
        log.setActionCode(command.actionCode());
        log.setEntityType(command.entityType());
        log.setEntityId(command.entityId());
        log.setBeforeData(auditDataRedactor.redact(command.beforeData()));
        log.setAfterData(auditDataRedactor.redact(command.afterData()));
        if (command.ipAddress() != null && !command.ipAddress().isBlank()) {
            try {
                log.setIpAddress(InetAddress.getByName(command.ipAddress()));
            } catch (Exception ignored) {
                // Invalid client IP is intentionally omitted from the audit record.
            }
        }
        return log;
    }
}
