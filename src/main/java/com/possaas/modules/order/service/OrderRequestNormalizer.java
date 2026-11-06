package com.possaas.modules.order.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.order.entity.OrderSubmissionMode;
import com.possaas.modules.order.entity.ServiceType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderRequestNormalizer {
    private final OrderMoneyCalculator money;
    public OrderRequests.Create normalize(OrderRequests.Create r) {
        if(r.sourceChannel()!=SourceChannel.CASHIER && r.sourceChannel()!=SourceChannel.WAITER)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_ORDER_SOURCE","Staff orders support CASHIER or WAITER only");
        requireSeatingShape(r);
        if(r.guestCount()!=null && (r.guestCount()<1 || r.guestCount()>Short.MAX_VALUE))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Guest count must be between 1 and 32767");
        return new OrderRequests.Create(r.serviceType(),r.sourceChannel(),r.tableSessionId(),r.guestCount(),text(r.customerName()),text(r.customerPhone()),text(r.note()),lines(r.items()),r.effectiveMode(),r.tableId());
    }
    private void requireSeatingShape(OrderRequests.Create r) {
        if(r.serviceType()==ServiceType.DINE_IN) {
            if(r.tableId()!=null && r.tableSessionId()!=null)
                throw new BusinessException(HttpStatus.BAD_REQUEST,"AMBIGUOUS_ORDER_SEATING","Supply tableId or tableSessionId, not both");
            if(r.tableId()==null && r.tableSessionId()==null)
                throw new BusinessException(HttpStatus.BAD_REQUEST,"DINE_IN_SESSION_REQUIRED","DINE_IN requires tableSessionId or tableId");
            if(r.tableId()!=null && r.effectiveMode()!=OrderSubmissionMode.SUBMIT)
                throw new BusinessException(HttpStatus.BAD_REQUEST,"TABLE_ORDER_REQUIRES_SUBMIT","Creating from tableId requires SUBMIT");
        } else if(r.serviceType()==ServiceType.TAKEAWAY) {
            if(r.tableSessionId()!=null)
                throw new BusinessException(HttpStatus.BAD_REQUEST,"TAKEAWAY_SESSION_NOT_ALLOWED","TAKEAWAY cannot have a table session");
            if(r.tableId()!=null)
                throw new BusinessException(HttpStatus.BAD_REQUEST,"TAKEAWAY_TABLE_NOT_ALLOWED","TAKEAWAY cannot have a table");
        } else {
            throw new BusinessException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_ORDER_SERVICE_TYPE","Unsupported service type");
        }
    }
    public OrderRequests.AddItems normalize(OrderRequests.AddItems r) {
        return new OrderRequests.AddItems(r.expectedVersion(),lines(r.items()),r.effectiveMode());
    }
    public List<OrderRequests.Line> lines(List<OrderRequests.Line> lines) {
        if(lines==null || lines.isEmpty() || lines.size()>100) throw new BusinessException(HttpStatus.BAD_REQUEST,"EMPTY_ORDER","Supply between one and 100 lines");
        return lines.stream().map(l->new OrderRequests.Line(l.itemId(),money.quantity(l.quantity()),text(l.note()))).toList();
    }
    public static String text(String value) { return value==null || value.isBlank()?null:value.trim(); }
}
