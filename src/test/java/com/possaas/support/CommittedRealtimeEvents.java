package com.possaas.support;

import com.possaas.modules.order.event.OrderChanged;
import com.possaas.modules.qrorder.event.QrOrderRequestChanged;
import com.possaas.modules.qrorder.event.QrOrderRequestsChanged;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.event.*;

/** Observes actual committed transactions, including worker-thread concurrent commands. */
public class CommittedRealtimeEvents {
    public final List<OrderChanged> orders = new CopyOnWriteArrayList<>();
    public final List<QrOrderRequestChanged> qr = new CopyOnWriteArrayList<>();
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT, fallbackExecution=false)
    public void order(OrderChanged event) { orders.add(event); }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT, fallbackExecution=false)
    public void qr(QrOrderRequestChanged event) { qr.add(event); }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT, fallbackExecution=false)
    public void qrBatch(QrOrderRequestsChanged event) { qr.addAll(event.requests()); }
    public void clear() { orders.clear(); qr.clear(); }
    @TestConfiguration public static class Config {
        @Bean CommittedRealtimeEvents committedRealtimeEvents() { return new CommittedRealtimeEvents(); }
    }
}
