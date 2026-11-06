package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.realtime.InventoryAlertChangedEvent;
import com.possaas.modules.inventory.dto.LowStockAlertRequests;
import com.possaas.modules.inventory.dto.LowStockAlertResponse;
import com.possaas.modules.inventory.entity.LowStockAlert;
import com.possaas.modules.inventory.repository.LowStockAlertBalanceQuery;
import com.possaas.modules.inventory.repository.LowStockAlertBalanceQuery.Balance;
import com.possaas.modules.inventory.repository.LowStockAlertQueryRepository;
import com.possaas.modules.inventory.repository.LowStockAlertRepository;
import com.possaas.modules.inventory.service.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class LowStockAlertRealtimeWiringTest {
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
    private final UUID tenant = UUID.randomUUID(), warehouse = UUID.randomUUID(), ingredient = UUID.randomUUID();
    private final CurrentUser actor = new CurrentUser(UUID.randomUUID(), tenant, "owner@test.invalid",
            "OWNER", Set.of("INVENTORY_VIEW", "INVENTORY_ALERT_ACKNOWLEDGE"));
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InventoryAccessService access = mock(InventoryAccessService.class);
    private final LowStockAlertBalanceQuery balances = mock(LowStockAlertBalanceQuery.class);
    private final LowStockAlertRepository alerts = mock(LowStockAlertRepository.class);
    private final LowStockAlertQueryRepository queries = mock(LowStockAlertQueryRepository.class);
    private final LowStockAlertPersistence persistence = mock(LowStockAlertPersistence.class);
    private final LowStockAlertAudit audit = mock(LowStockAlertAudit.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final LowStockAlertEventPublisher realtime = new LowStockAlertEventPublisher(events, clock);
    private final LowStockAlertPolicy policy = new LowStockAlertPolicy();
    private final LowStockAlertReconciliationService reconciliation = new LowStockAlertReconciliationService(
            balances, alerts, policy, persistence, audit, realtime, entityManager, clock);
    private final LowStockAlertCommandService commands = new LowStockAlertCommandService(
            access, alerts, queries, policy, persistence, audit, realtime, clock);
    private final List<InventoryAlertChangedEvent> published = new ArrayList<>();

    @BeforeEach void persistedVersionsAreObservableOnlyAfterFlush() {
        when(access.requireLowStock(true)).thenReturn(actor);
        when(alerts.findUnresolvedForUpdate(eq(tenant), anyCollection(), anyCollection())).thenReturn(List.of());
        when(balances.find(eq(tenant), anyCollection(), anyCollection())).thenReturn(List.of(balance("g", "5")));
        when(audit.snapshot(any())).thenReturn(Map.of());
        doAnswer(call -> {
            Collection<LowStockAlert> changed = call.getArgument(0);
            for (var alert : changed) {
                if (alert.getId() == null) ReflectionTestUtils.setField(alert, "id", UUID.randomUUID());
                ReflectionTestUtils.setField(alert, "version", 12L);
            }
            return null;
        }).when(persistence).save(anyCollection());
        doAnswer(call -> {
            published.add(call.getArgument(0, InventoryAlertChangedEvent.class));
            return null;
        }).when(events).publishEvent(any(InventoryAlertChangedEvent.class));
    }

    @Test void newShortageEmitsOpenWithTheFlushedIdAndVersionAfterAudit() {
        reconciliation.warehouseChanged(actor, warehouse, null);
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().envelope().resourceId()).isNotNull();
        assertThat(published.getFirst().envelope().resourceVersion()).isEqualTo(12);
        assertThat(published.getFirst().envelope().payload()).containsEntry("status", "OPEN");
        var order = inOrder(audit, events);
        order.verify(audit).transitions(eq(actor.userId()), anyList(), anyMap(), isNull());
        order.verify(events).publishEvent(any(InventoryAlertChangedEvent.class));
    }

    @Test void unchangedShortageDoesNotRepeatTheOpenEvent() {
        when(alerts.findUnresolvedForUpdate(eq(tenant), anyCollection(), anyCollection())).thenReturn(List.of(alert()));
        reconciliation.warehouseChanged(actor, warehouse, null);
        assertThat(published).isEmpty();
        verify(audit, never()).transitions(any(), anyList(), anyMap(), any());
    }

    @Test void recoveredPhysicalStockEmitsResolvedAfterItsVersionFlush() {
        when(alerts.findUnresolvedForUpdate(eq(tenant), anyCollection(), anyCollection())).thenReturn(List.of(alert()));
        when(balances.find(eq(tenant), anyCollection(), anyCollection())).thenReturn(List.of(balance("g", "11")));
        reconciliation.warehouseChanged(actor, warehouse, null);
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().envelope().resourceVersion()).isEqualTo(12);
        assertThat(published.getFirst().envelope().payload()).containsEntry("status", "RESOLVED")
                .containsEntry("resolutionReason", "STOCK_RECOVERED");
    }

    @Test void unitChangeEmitsClosureThenTheNewEpisodeWithDistinctIds() {
        var existing = alert();
        when(alerts.findUnresolvedForUpdate(eq(tenant), anyCollection(), anyCollection())).thenReturn(List.of(existing));
        when(balances.find(eq(tenant), anyCollection(), anyCollection())).thenReturn(List.of(balance("kg", "5")));
        reconciliation.ingredientChanged(actor, ingredient, null);
        assertThat(published).extracting(event -> event.envelope().payload().get("status"))
                .containsExactly("RESOLVED", "OPEN");
        assertThat(published.getFirst().envelope().payload()).containsEntry("resolutionReason", "INGREDIENT_UNIT_CHANGED");
        assertThat(published).extracting(event -> event.envelope().resourceId()).doesNotHaveDuplicates();
    }

    @Test void failedPersistenceDoesNotEmitAnEvent() {
        doThrow(new IllegalStateException("write failed")).when(persistence).save(argThat(batch -> !batch.isEmpty()));
        assertThatThrownBy(() -> reconciliation.warehouseChanged(actor, warehouse, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(published).isEmpty();
    }

    @Test void acknowledgeEmitsTheUpdatedVersionButReplayDoesNotEmitAgain() {
        var existing = alert();
        when(alerts.findForUpdate(tenant, existing.getId())).thenReturn(Optional.of(existing));
        when(queries.find(tenant, existing.getId())).thenReturn(Optional.of(mock(LowStockAlertResponse.class)));
        commands.acknowledge(existing.getId(), new LowStockAlertRequests.Acknowledge(4L), null);
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().envelope().resourceVersion()).isEqualTo(12);
        assertThat(published.getFirst().envelope().payload()).containsEntry("status", "ACKNOWLEDGED");
        commands.acknowledge(existing.getId(), new LowStockAlertRequests.Acknowledge(4L), null);
        assertThat(published).hasSize(1);
    }

    @Test void aStaleAcknowledgeDoesNotPublish() {
        var existing = alert();
        when(alerts.findForUpdate(tenant, existing.getId())).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> commands.acknowledge(existing.getId(), new LowStockAlertRequests.Acknowledge(99L), null))
                .isInstanceOf(BusinessException.class);
        assertThat(published).isEmpty();
        verifyNoInteractions(persistence);
    }

    private LowStockAlert alert() {
        var alert = new LowStockAlert(tenant, warehouse, ingredient, "MAIN", "Main", "BEEF", "Beef", "g",
                new BigDecimal("5"), BigDecimal.TEN, NOW);
        ReflectionTestUtils.setField(alert, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(alert, "version", 4L);
        return alert;
    }

    private Balance balance(String unit, String quantity) {
        return new Balance(warehouse, ingredient, "MAIN", "Main", true, false, "BEEF", "Beef", unit,
                true, false, new BigDecimal(quantity), BigDecimal.TEN);
    }
}
