package com.possaas.modules.qrorder.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.qrorder.entity.QrOrderRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class QrRequestAudit {
    private final AuditService audit;
    public Map<String,Object> snapshot(QrOrderRequest r) {
        var safe = new LinkedHashMap<String,Object>();
        safe.put("requestId", r.getId()); safe.put("guestId", r.getGuestId()); safe.put("entryType", r.getEntryType());
        safe.put("serviceType", r.getEntryType().serviceType()); safe.put("sourceChannel", r.getEntryType().sourceChannel());
        safe.put("status", r.getStatus()); safe.put("version", r.getVersion()); safe.put("approvedOrderId", r.getApprovedOrderId());
        safe.put("approvedSessionId", r.getApprovedSessionId()); safe.put("approvedAmount", r.getApprovedAmount());
        safe.put("cancellationReason", r.getCancellationReason());
        return safe;
    }
    public void record(QrOrderRequest r, UUID actor, String action, Map<String,Object> before, String ip) {
        audit.record(r.getRestaurantId(), actor, action, "qr_order_requests", r.getId(), before, snapshot(r), ip);
    }
}
