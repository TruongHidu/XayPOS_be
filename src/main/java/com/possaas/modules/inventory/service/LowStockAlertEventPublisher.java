package com.possaas.modules.inventory.service;

import com.possaas.infrastructure.realtime.InventoryAlertChangedEvent;
import com.possaas.infrastructure.realtime.RealtimeEventEnvelope;
import com.possaas.modules.inventory.entity.LowStockAlert;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Takes snapshots only after persistence has assigned IDs and flushed lock versions. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class LowStockAlertEventPublisher {
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public void publish(Collection<LowStockAlert> alerts) {
        var occurredAt = clock.instant();
        for (var alert : alerts) {
            if (alert.getId() == null) {
                throw new IllegalStateException("Realtime alert must be persisted first");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("warehouseId", alert.getWarehouseId());
            payload.put("ingredientId", alert.getItemId());
            payload.put("status", alert.getStatus().name());
            payload.put("resolutionReason", alert.getResolutionReason() == null
                    ? null : alert.getResolutionReason().name());
            events.publishEvent(new InventoryAlertChangedEvent(alert.getRestaurantId(),
                    new RealtimeEventEnvelope(UUID.randomUUID(), "INVENTORY_LOW_STOCK_ALERT_CHANGED", 1,
                            "LOW_STOCK_ALERT", alert.getId(), alert.getVersion(), occurredAt, payload)));
        }
    }
}
