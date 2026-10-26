package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.possaas.infrastructure.realtime.InventoryAlertChangedEvent;
import com.possaas.infrastructure.realtime.RealtimeEventEnvelope;
import com.possaas.modules.inventory.entity.LowStockAlert;
import com.possaas.modules.inventory.entity.LowStockAlertResolutionReason;
import com.possaas.modules.inventory.service.LowStockAlertEventPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class LowStockAlertEventPublisherTest {
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final LowStockAlertEventPublisher publisher = new LowStockAlertEventPublisher(
            events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void persistedOpenPublishesOnlyAnImmutableTenantScopedInvalidationHint() {
        var alert = alert();
        var published = capture();
        publisher.publish(List.of(alert));

        assertThat(published).hasSize(1);
        var event = published.getFirst();
        var envelope = event.envelope();
        assertThat(event.restaurantId()).isEqualTo(alert.getRestaurantId());
        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.type()).isEqualTo("INVENTORY_LOW_STOCK_ALERT_CHANGED");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.resourceType()).isEqualTo("LOW_STOCK_ALERT");
        assertThat(envelope.resourceId()).isEqualTo(alert.getId());
        assertThat(envelope.resourceVersion()).isEqualTo(7);
        assertThat(envelope.occurredAt()).isEqualTo(NOW);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("warehouseId", alert.getWarehouseId());
        expected.put("ingredientId", alert.getItemId());
        expected.put("status", "OPEN");
        expected.put("resolutionReason", null);
        assertThat(envelope.payload()).containsExactlyInAnyOrderEntriesOf(expected);
        assertThatThrownBy(() -> envelope.payload().put("status", "RESOLVED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void laterEntityMutationsDoNotRewriteAPreviouslyPublishedEvent() {
        var alert = alert();
        var published = capture();
        publisher.publish(List.of(alert));
        alert.acknowledge(UUID.randomUUID(), NOW.plusSeconds(1));
        ReflectionTestUtils.setField(alert, "version", 8L);
        publisher.publish(List.of(alert));
        alert.resolve(LowStockAlertResolutionReason.STOCK_RECOVERED, NOW.plusSeconds(2));
        ReflectionTestUtils.setField(alert, "version", 9L);
        publisher.publish(List.of(alert));

        assertThat(published).extracting(event -> event.envelope().payload().get("status"))
                .containsExactly("OPEN", "ACKNOWLEDGED", "RESOLVED");
        assertThat(published).extracting(event -> event.envelope().resourceVersion())
                .containsExactly(7L, 8L, 9L);
        assertThat(published.getLast().envelope().payload().get("resolutionReason"))
                .isEqualTo("STOCK_RECOVERED");
        assertThat(published).extracting(event -> event.envelope().eventId()).doesNotHaveDuplicates();
    }

    @Test void transientEntitiesCannotPublishAHintBeforePersistenceAssignsAnId() {
        var alert = alert();
        ReflectionTestUtils.setField(alert, "id", null);
        assertThatThrownBy(() -> publisher.publish(List.of(alert)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
    }

    @Test void anEmptyTransitionBatchPublishesNothing() {
        publisher.publish(List.of());
        verifyNoInteractions(events);
    }

    @Test void envelopeCopiesItsPayloadAndRetainsNullableResolutionReason() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "OPEN");
        payload.put("resolutionReason", null);
        var envelope = new RealtimeEventEnvelope(UUID.randomUUID(), "TYPE", 1, "RESOURCE",
                UUID.randomUUID(), 0, NOW, payload);
        payload.put("status", "RESOLVED");
        assertThat(envelope.payload()).containsEntry("status", "OPEN")
                .containsEntry("resolutionReason", null);
    }

    private List<InventoryAlertChangedEvent> capture() {
        var published = new ArrayList<InventoryAlertChangedEvent>();
        doAnswer(call -> {
            published.add(call.getArgument(0, InventoryAlertChangedEvent.class));
            return null;
        }).when(events).publishEvent(any(InventoryAlertChangedEvent.class));
        return published;
    }

    private LowStockAlert alert() {
        var alert = new LowStockAlert(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MAIN", "Main", "BEEF", "Beef", "g", BigDecimal.ONE, BigDecimal.TEN, NOW);
        ReflectionTestUtils.setField(alert, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(alert, "version", 7L);
        return alert;
    }
}
