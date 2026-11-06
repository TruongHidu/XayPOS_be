package com.possaas.modules.qrorder.service;

import com.possaas.modules.qrorder.entity.QrOrderRequest;
import com.possaas.modules.qrorder.event.QrOrderRequestChanged;
import com.possaas.modules.qrorder.event.QrOrderRequestsChanged;
import java.util.List;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class QrRequestEventPublisher {
    private final ApplicationEventPublisher events;
    private final Clock clock;
    public void publish(QrOrderRequest request, QrOrderRequestChanged.Change change) {
        events.publishEvent(snapshot(request,change));
    }
    public void cancelled(List<QrOrderRequest> requests) {
        for (int start = 0; start < requests.size(); start += 100) {
            var batch = requests.subList(start, Math.min(start + 100,requests.size())).stream()
                    .map(r -> snapshot(r,QrOrderRequestChanged.Change.CANCELLED)).toList();
            events.publishEvent(new QrOrderRequestsChanged(batch.getFirst().restaurantId(),batch));
        }
    }
    private QrOrderRequestChanged snapshot(QrOrderRequest request, QrOrderRequestChanged.Change change) {
        return new QrOrderRequestChanged(UUID.randomUUID(), request.getRestaurantId(), request.getId(),
                request.getVersion(), clock.instant(), request.getStatus(), change, request.getEntryType(),
                request.getTableId(), request.getObservedSessionId(), request.getApprovedOrderId(), request.getApprovedSessionId());
    }
}
