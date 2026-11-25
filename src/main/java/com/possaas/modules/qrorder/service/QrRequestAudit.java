package com.possaas.modules.qrorder.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.qrorder.entity.QrOrderRequest;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** Left-out lines are audited by id only; their free-text reasons stay out of the audit log. */
    public void recordApproval(QrOrderRequest r, UUID actor, Map<String,Object> before, List<UUID> excludedRequestItemIds, String ip) {
        var after = snapshot(r); after.put("excludedRequestItemIds", List.copyOf(excludedRequestItemIds));
        audit.record(r.getRestaurantId(), actor, "QR_ORDER_REQUEST_APPROVED", "qr_order_requests", r.getId(), before, after, ip);
    }
}
