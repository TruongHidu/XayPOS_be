package com.possaas.modules.order.service;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.service.strategy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderFactory {
    private final OrderCreationStrategyRegistry registry;
    public OrderCreationPlan prepare(ServiceType type,OrderCreationContext context) { return registry.get(type).prepare(context); }
}
