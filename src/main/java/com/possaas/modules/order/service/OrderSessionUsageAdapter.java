package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.table.application.port.TableSessionUsagePolicy;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class OrderSessionUsageAdapter implements TableSessionUsagePolicy {
    private final OrderRepository orders;
    @Override public void requireCancellable(UUID tenant,UUID session) {
        if(orders.hasBlockingSessionOrders(tenant,session))
            throw new ConflictException("TABLE_SESSION_IN_USE","Session has active orders or financial obligations");
    }
}
