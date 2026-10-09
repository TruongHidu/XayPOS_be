package com.possaas.modules.order.service.strategy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.ServiceType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TakeawayOrderCreationStrategy implements OrderCreationStrategy {
    @Override public ServiceType supportedType() { return ServiceType.TAKEAWAY; }
    @Override public OrderCreationPlan prepare(OrderCreationContext context) {
        if(context.tableSessionId()!=null) throw new BusinessException(HttpStatus.BAD_REQUEST,"TAKEAWAY_SESSION_NOT_ALLOWED","TAKEAWAY cannot have a table session");
        if(context.tableId()!=null) throw new BusinessException(HttpStatus.BAD_REQUEST,"TAKEAWAY_TABLE_NOT_ALLOWED","TAKEAWAY cannot have a table");
        return new OrderCreationPlan(null,context.guestCount()==null?(short)1:context.guestCount().shortValue());
    }
}
