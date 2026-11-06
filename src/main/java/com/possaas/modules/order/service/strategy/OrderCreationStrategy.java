package com.possaas.modules.order.service.strategy;

import com.possaas.modules.order.entity.ServiceType;

public interface OrderCreationStrategy {
    ServiceType supportedType();
    OrderCreationPlan prepare(OrderCreationContext context);
}
