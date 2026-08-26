package com.possaas.modules.audit.service;

import com.possaas.modules.audit.entity.AuditLog;
import com.possaas.modules.audit.repository.AuditLogRepository;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repository;

    @Transactional
    public void record(UUID restaurantId, UUID actorUserId, String actionCode, String entityType,
                       UUID entityId, Map<String, Object> beforeData, Map<String, Object> afterData, String ip) {
        AuditLog log = new AuditLog();
        log.setRestaurantId(restaurantId);
        log.setActorUserId(actorUserId);
        log.setActionCode(actionCode);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setBeforeData(beforeData);
        log.setAfterData(afterData);
        if (ip != null && !ip.isBlank()) {
            try { log.setIpAddress(InetAddress.getByName(ip)); } catch (Exception ignored) { }
        }
        repository.save(log);
    }
}
