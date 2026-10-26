package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.dto.*;
import com.possaas.modules.inventory.repository.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class LowStockAlertCommandService {
    private final InventoryAccessService access;
    private final LowStockAlertRepository alerts;
    private final LowStockAlertQueryRepository queries;
    private final LowStockAlertPolicy policy;
    private final LowStockAlertPersistence persistence;
    private final LowStockAlertAudit audit;
    private final LowStockAlertEventPublisher realtime;
    private final Clock clock;

    public LowStockAlertResponse acknowledge(UUID id,LowStockAlertRequests.Acknowledge request,String ip) {
        var actor=access.requireLowStock(true);
        var alert=alerts.findForUpdate(actor.restaurantId(),id).orElseThrow(LowStockAlertQueryService::notFound);
        if (policy.requireAcknowledge(alert,request.expectedVersion())) {
            var before=audit.snapshot(alert);alert.acknowledge(actor.userId(),clock.instant());
            persistence.save(List.of(alert));audit.transitions(actor.userId(),List.of(alert),Map.of(id,before),ip);
            realtime.publish(List.of(alert));
        }
        return queries.find(actor.restaurantId(),id).orElseThrow(LowStockAlertQueryService::notFound);
    }
}
