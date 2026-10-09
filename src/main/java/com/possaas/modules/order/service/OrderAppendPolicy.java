package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.table.application.port.OrderSeatingQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Appending new lines deliberately has a different boundary from editing old data. */
@Component @RequiredArgsConstructor
public class OrderAppendPolicy {
    private final OrderLifecyclePolicy lifecycle;
    private final OrderSeatingQuery seating;

    public void requireAppendable(Order order,OrderSubmissionMode mode,String restaurantCurrency) {
        if(mode==OrderSubmissionMode.DRAFT) lifecycle.requireEditable(order);
        else {
            if(order.getStatus()!=OrderStatus.OPEN && order.getStatus()!=OrderStatus.CONFIRMED)
                throw new ConflictException("INVALID_ORDER_TRANSITION","Only OPEN or CONFIRMED orders accept submitted additions");
            lifecycle.requireUnpaid(order);
        }
        if(!order.getCurrencyCode().equals(restaurantCurrency))
            throw new ConflictException("ORDER_CURRENCY_MISMATCH","Restaurant currency changed; create a new order");
        if(mode==OrderSubmissionMode.SUBMIT && order.getServiceType()==ServiceType.DINE_IN)
            seating.requireOpen(order.getRestaurantId(),order.getTableSessionId());
    }
}
