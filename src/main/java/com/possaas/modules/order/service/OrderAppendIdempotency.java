package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.OrderItemSubmission;
import com.possaas.modules.order.repository.OrderItemSubmissionRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderAppendIdempotency {
    private final OrderItemSubmissionRepository submissions;

    public boolean replay(UUID tenant,UUID orderId,UUID actor,String key,String hash) {
        var existing=submissions.findByRestaurantIdAndIdempotencyKey(tenant,key);
        if(existing.isEmpty()) return false;
        var recorded=existing.get();
        if(!orderId.equals(recorded.getOrderId()) || !actor.equals(recorded.getActorUserId()) || !hash.equals(recorded.getRequestHash()))
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED","Append key was already used for a different order, request or actor");
        return true;
    }
    public void record(UUID tenant,UUID orderId,UUID actor,String key,String hash,Instant now) {
        submissions.saveAndFlush(new OrderItemSubmission(tenant,orderId,actor,key,hash,now));
    }
}
