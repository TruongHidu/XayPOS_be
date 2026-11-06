package com.possaas.infrastructure.realtime;

import com.possaas.modules.order.event.OrderChanged;
import com.possaas.modules.qrorder.event.QrOrderRequestChanged;
import com.possaas.modules.qrorder.event.QrOrderRequestsChanged;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component @RequiredArgsConstructor
public class OrderRealtimeAdapter {
    private static final Logger log = LoggerFactory.getLogger(OrderRealtimeAdapter.class);
    private final RealtimeDispatcher dispatcher;
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void changed(OrderChanged event) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("orderStatus", event.status().name()); payload.put("tableSessionId", event.tableSessionId());
            payload.put("changes", event.changes().stream().map(Enum::name).toList());
            payload.put("changedOrderItemIds", event.changedOrderItemIds()); payload.put("newKitchenItemIds", event.newKitchenItemIds());
            var envelope = new RealtimeEventEnvelope(event.eventId(), "ORDER_CHANGED", 1, "ORDER", event.orderId(),
                    event.version(), event.occurredAt(), payload);
            dispatcher.enqueue(event.restaurantId(), envelope, event.kitchenRelevant()
                    ? Set.of(RealtimeChannel.ORDERS, RealtimeChannel.KITCHEN) : Set.of(RealtimeChannel.ORDERS));
        } catch (RuntimeException invalid) { log.warn("Order realtime mapping dropped"); }
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void changed(QrOrderRequestChanged event) {
        try {
            dispatcher.enqueue(event.restaurantId(), qrEnvelope(event), Set.of(RealtimeChannel.QR_REQUESTS));
        } catch (RuntimeException invalid) { log.warn("QR realtime mapping dropped"); }
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void changed(QrOrderRequestsChanged batch) {
        try {
            dispatcher.enqueueBatch(batch.restaurantId(), batch.requests().stream().map(this::qrEnvelope).toList(),
                    Set.of(RealtimeChannel.QR_REQUESTS));
        } catch (RuntimeException invalid) { log.warn("QR realtime batch mapping dropped"); }
    }
    private RealtimeEventEnvelope qrEnvelope(QrOrderRequestChanged event) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("status", event.status().name()); payload.put("changeKind", event.changeKind().name());
            payload.put("entryType", event.entryType().name()); payload.put("tableId", event.tableId());
            payload.put("observedSessionId", event.observedSessionId()); payload.put("approvedOrderId", event.approvedOrderId());
            payload.put("approvedSessionId", event.approvedSessionId());
            return new RealtimeEventEnvelope(event.eventId(), "QR_ORDER_REQUEST_CHANGED",
                    1, "QR_ORDER_REQUEST", event.requestId(), event.version(), event.occurredAt(), payload);
    }
}
