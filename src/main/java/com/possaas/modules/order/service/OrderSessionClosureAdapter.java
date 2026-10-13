package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.table.application.port.TableSessionClosureReadiness;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class OrderSessionClosureAdapter implements TableSessionClosureReadiness {
    private final OrderRepository orders;

    @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public void requireClosable(UUID restaurantId,UUID sessionId) {
        var attached=orders.findAllByRestaurantIdAndTableSessionId(restaurantId,sessionId);
        boolean hasCompleted=false;
        for(var order:attached) {
            if(order.getStatus()==OrderStatus.COMPLETED && order.getPaymentStatus()==OrderPaymentStatus.PAID
                    && order.getTotalAmount().signum()>0 && order.getPaidAmount().compareTo(order.getTotalAmount())==0) {
                hasCompleted=true;
                continue;
            }
            if(order.getStatus()==OrderStatus.CANCELLED && order.getPaymentStatus()==OrderPaymentStatus.UNPAID
                    && order.getPaidAmount().signum()==0) continue;
            throw new ConflictException("TABLE_SESSION_NOT_READY_TO_CLOSE","Session has an unfinished or unsettled order");
        }
        if(!hasCompleted)
            throw new ConflictException("TABLE_SESSION_NOT_READY_TO_CLOSE","Session has no completed paid order");
    }
}
