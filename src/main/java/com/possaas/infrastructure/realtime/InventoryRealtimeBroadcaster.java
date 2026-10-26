package com.possaas.infrastructure.realtime;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Inventory retains its original wire contract on the shared transport. */
@Component @RequiredArgsConstructor
public class InventoryRealtimeBroadcaster {
    private final RealtimeDispatcher dispatcher;
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void changed(InventoryAlertChangedEvent event) {
        try { dispatcher.enqueue(event.restaurantId(), event.envelope(), Set.of(RealtimeChannel.INVENTORY_ALERTS)); }
        catch (RuntimeException unavailable) { /* Never fail a committed inventory operation. */ }
    }
}
