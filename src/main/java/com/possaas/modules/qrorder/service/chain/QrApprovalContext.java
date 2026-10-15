package com.possaas.modules.qrorder.service.chain;

import com.possaas.modules.order.application.port.QrApprovedOrderCommand;
import com.possaas.modules.order.service.OrderAccessService;
import com.possaas.modules.qrorder.dto.QrStaffRequests;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.service.QrReviewContextService;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public final class QrApprovalContext {
    private final OrderAccessService.Locked locked;
    private final Instant now;
    private final String ip;
    private final QrStaffRequests.Approve command;
    private final QrOrderRequest request;
    private final List<QrOrderRequestItem> items;
    private QrReviewContextService.Review review;
    private QrApprovedOrderCommand.Preparation prepared;
    private boolean persisted;
    public QrApprovalContext(OrderAccessService.Locked locked, Instant now, String ip, QrStaffRequests.Approve command,
            QrOrderRequest request, List<QrOrderRequestItem> items) {
        this.locked = locked; this.now = now; this.ip = ip; this.command = command; this.request = request; this.items = List.copyOf(items);
    }
    @Override public String toString() { return "QrApprovalContext[requestId=" + request.getId() + ", private fields omitted]"; }
}
