package com.possaas.modules.qrorder.service.chain;

import com.possaas.modules.order.application.port.QrApprovedOrderCommand;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.qrorder.dto.QrApprovalResponse;
import com.possaas.modules.qrorder.service.QrApprovalTargetPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class QrApprovalPrepareHandler implements OrderHandler<QrApprovalContext,QrApprovalResponse> {
    private final QrApprovalTargetPolicy targets;
    private final QrApprovedOrderCommand orders;
    @Override public QrApprovalResponse handle(QrApprovalContext c, OrderNext<QrApprovalContext,QrApprovalResponse> next) {
        var r = c.getRequest(); targets.require(r, c.getCommand().getTargetSessionId(), c.getReview());
        var quote = c.getReview().quote();
        var intent = new QrApprovedOrderCommand.Intent(r.getId(), r.getPayloadHash(), c.getLocked().actor(),
            r.getEntryType().serviceType(), r.getEntryType().sourceChannel(), r.getTableId(), c.getCommand().getTargetSessionId(),
            quote.currencyCode(), r.getCustomerName(), r.getCustomerPhone(), r.getNote(),
            c.getItems().stream().map(line -> new OrderRequests.Line(line.getItemId(), line.getQuantity(), line.getNote())).toList(),
            quote.lines().stream().map(line -> new QrApprovedOrderCommand.QuoteLine(line.itemId(), line.itemName(), line.unit(),
                line.quantity(), line.unitPrice(), line.lineTotal())).toList(), c.getNow(), c.getIp());
        c.setPrepared(orders.prepare(intent));
        return next.proceed(c);
    }
}
