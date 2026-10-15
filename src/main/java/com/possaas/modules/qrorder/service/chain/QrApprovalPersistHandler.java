package com.possaas.modules.qrorder.service.chain;

import com.possaas.modules.order.application.port.QrApprovedOrderCommand;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.qrorder.dto.QrApprovalResponse;
import com.possaas.modules.qrorder.entity.QrRequestStatus;
import com.possaas.modules.qrorder.repository.*;
import com.possaas.modules.qrorder.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class QrApprovalPersistHandler implements OrderHandler<QrApprovalContext,QrApprovalResponse> {
    private final QrApprovedOrderCommand orders;
    private final QrOrderRequestRepository requests;
    private final QrOrderRequestItemRepository items;
    private final QrRequestAudit audit;
    private final QrStaffResponseMapper mapper;
    @Override public QrApprovalResponse handle(QrApprovalContext c, OrderNext<QrApprovalContext,QrApprovalResponse> next) {
        var r = c.getRequest(); var before = audit.snapshot(r); var approved = orders.persist(c.getPrepared());
        if (approved.createdOrderItemIds().size() != c.getItems().size()) throw new IllegalStateException("Approval linkage mismatch");
        for (int i = 0; i < c.getItems().size(); i++) {
            c.getItems().get(i).setApprovedOrderItemId(approved.createdOrderItemIds().get(i));
            c.getItems().get(i).setUpdatedAt(c.getNow());
        }
        items.saveAllAndFlush(c.getItems());
        r.setStatus(QrRequestStatus.APPROVED); r.setApprovedOrderId(approved.order().id());
        r.setApprovedSessionId(approved.order().tableSessionId()); r.setApprovedBy(c.getLocked().actor().userId());
        r.setApprovedAt(c.getNow()); r.setApprovedAmount(c.getReview().quote().amount());
        r.setApprovedOrderCode(approved.order().orderCode()); r.setUpdatedAt(c.getNow()); requests.saveAndFlush(r);
        audit.record(r, c.getLocked().actor().userId(), "QR_ORDER_REQUEST_APPROVED", before, c.getIp()); requests.flush();
        c.setPersisted(true);
        return next.complete(mapper.approved(r, c.getItems(), approved.order(), false));
    }
}
