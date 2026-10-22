package com.possaas.modules.inventory.service;

import com.possaas.modules.audit.service.*;
import com.possaas.modules.inventory.entity.LowStockAlert;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class LowStockAlertAudit {
    private final AuditService audits;

    public Map<String,Object> snapshot(LowStockAlert alert) {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("alertId",alert.getId());data.put("warehouseId",alert.getWarehouseId());
        data.put("ingredientId",alert.getItemId());data.put("status",alert.getStatus());
        data.put("quantityAtTrigger",alert.getQuantityAtTrigger());data.put("thresholdQuantity",alert.getThresholdQuantity());
        data.put("resolutionReason",alert.getResolutionReason());data.put("acknowledgedBy",alert.getAcknowledgedBy());
        return data;
    }

    public void transitions(UUID actor,List<LowStockAlert> changed,Map<UUID,Map<String,Object>> before,String ip) {
        audits.recordAll(changed.stream().map(alert->new AuditRecordCommand(alert.getRestaurantId(),actor,
            "LOW_STOCK_ALERT_"+switch(alert.getStatus()) {
                case OPEN -> "TRIGGERED";case ACKNOWLEDGED -> "ACKNOWLEDGED";case RESOLVED -> "RESOLVED";
            },"low_stock_alerts",alert.getId(),before.get(alert.getId()),snapshot(alert),ip)).toList());
    }
}
