package com.possaas.modules.qrorder.service.chain;

import com.possaas.modules.qrorder.event.QrOrderRequestChanged.Change;
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
    private final QrRequestEventPublisher events;
    private final QrOrderRequestRepository requests;
    private final QrOrderRequestItemRepository items;
    private final QrRequestAudit audit;
    private final QrStaffResponseMapper mapper;
    @Override public QrApprovalResponse handle(QrApprovalContext c, OrderNext<QrApprovalContext,QrApprovalResponse> next) {
        var r = c.getRequest(); var decision = c.getDecision(); var before = audit.snapshot(r);
        var approved = orders.persist(c.getPrepared());
        var included = decision.included();
        if (approved.createdOrderItemIds().size() != included.size()) throw new IllegalStateException("Approval linkage mismatch");
        for (int i = 0; i < included.size(); i++) {
            included.get(i).setApprovedOrderItemId(approved.createdOrderItemIds().get(i));
            included.get(i).setUpdatedAt(c.getNow());
        }
        decision.excluded().forEach(left -> left.item().reject(left.reason(), c.getNow()));
        items.saveAllAndFlush(c.getItems());
        r.setStatus(QrRequestStatus.APPROVED); r.setApprovedOrderId(approved.order().id());
        r.setApprovedSessionId(approved.order().tableSessionId()); r.setApprovedBy(c.getLocked().actor().userId());
        r.setApprovedAt(c.getNow()); r.setApprovedAmount(decision.approvedAmount());
        r.setApprovedOrderCode(approved.order().orderCode()); r.setUpdatedAt(c.getNow()); requests.saveAndFlush(r);
        audit.recordApproval(r, c.getLocked().actor().userId(), before, decision.excludedIds(), c.getIp()); requests.flush();
        events.publish(r,Change.APPROVED);
        c.setPersisted(true);
        return next.complete(mapper.approved(r, c.getItems(), approved.order(), false));
    }
}
