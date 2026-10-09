package com.possaas.modules.order.service.strategy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.table.application.port.OrderSeatingQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class DineInOrderCreationStrategy implements OrderCreationStrategy {
    private final OrderSeatingQuery seating;
    @Override public ServiceType supportedType() { return ServiceType.DINE_IN; }
    @Override public OrderCreationPlan prepare(OrderCreationContext context) {
        if(context.tableId()!=null) {
            if(context.tableSessionId()!=null) throw new BusinessException(HttpStatus.BAD_REQUEST,"AMBIGUOUS_ORDER_SEATING","Supply one seating reference");
            return switch(seating.resolveTable(context.restaurantId(),context.tableId())) {
                case OrderSeatingQuery.Existing existing -> new OrderCreationPlan(existing.session().sessionId(),
                    guests(context.guestCount(),existing.session().guestCount()));
                case OrderSeatingQuery.Unoccupied unoccupied -> OrderCreationPlan.newSession(unoccupied.tableId(),
                    guests(context.guestCount(),(short)1));
            };
        }
        if(context.tableSessionId()==null) throw new BusinessException(HttpStatus.BAD_REQUEST,"DINE_IN_SESSION_REQUIRED","DINE_IN requires a table session");
        var resolved=seating.requireOpen(context.restaurantId(),context.tableSessionId());
        return new OrderCreationPlan(resolved.sessionId(),guests(context.guestCount(),resolved.guestCount()));
    }
    private short guests(Integer value,short fallback) {
        if(value!=null && (value<1 || value>Short.MAX_VALUE))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Guest count must be between 1 and 32767");
        return value==null?fallback:value.shortValue();
    }
}
