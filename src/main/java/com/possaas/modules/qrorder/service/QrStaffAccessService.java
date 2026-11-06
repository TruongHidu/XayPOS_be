package com.possaas.modules.qrorder.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.service.OrderAccessService;
import com.possaas.modules.qrorder.entity.QrOrderRequest;
import com.possaas.modules.qrorder.repository.QrOrderRequestRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class QrStaffAccessService {
    private final OrderAccessService access;
    private final QrOrderRequestRepository requests;
    public CurrentUser read() { var actor = access.read(); require(actor, "ORDER_VIEW"); return actor; }
    public OrderAccessService.Locked lockApprove() {
        var locked = access.lock(); require(locked.actor(), "ORDER_CREATE"); require(locked.actor(), "ORDER_UPDATE"); return locked;
    }
    public OrderAccessService.Locked lockReject() { var locked = access.lock(); require(locked.actor(), "ORDER_UPDATE"); return locked; }
    public QrOrderRequest request(UUID tenant, UUID id) {
        return requests.findByIdAndRestaurantId(id, tenant)
            .orElseThrow(() -> new ResourceNotFoundException("QR_ORDER_REQUEST_NOT_FOUND", "QR request not found"));
    }
    private void require(CurrentUser actor, String permission) {
        if (!actor.hasPermission(permission)) throw new AccessDeniedException("Required QR review permission is missing");
    }
}
