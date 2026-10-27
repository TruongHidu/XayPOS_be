package com.possaas.modules.inventory;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.IngredientReferenceCostWriter;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentType;
import com.possaas.modules.inventory.repository.InventoryBalanceRepository;
import com.possaas.modules.inventory.service.ReceiptReferenceCostService;
import com.possaas.modules.inventory.service.WeightedAverageCostPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReceiptReferenceCostServiceTest {
    private final InventoryBalanceRepository balances = mock(InventoryBalanceRepository.class);
    private final IngredientReferenceCostWriter costs = mock(IngredientReferenceCostWriter.class);
    private final ReceiptReferenceCostService service = new ReceiptReferenceCostService(
            balances, costs, new WeightedAverageCostPolicy(), mock(com.possaas.modules.audit.service.AuditService.class));
    private final CurrentUser actor = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(),
            "owner@example.com", "OWNER", Set.of("INVENTORY_RECEIVE"));
    private final Instant now = Instant.parse("2026-10-19T08:00:00Z");

    @Test
    void locksPricesBeforeReadingTenantWidePhysicalQuantitiesAndUpdatesOneBatch() {
        UUID id = UUID.randomUUID();
        StockDocument document = document(StockDocumentType.RECEIPT);
        var quantity = mock(InventoryBalanceRepository.IngredientTotalQuantity.class);
        when(quantity.getItemId()).thenReturn(id);
        when(quantity.getQuantity()).thenReturn(new BigDecimal("10"));
        when(costs.lockReferenceCostStates(actor.restaurantId(), List.of(id)))
                .thenReturn(Map.of(id, new IngredientReferenceCostWriter.ReferenceCost(new BigDecimal("100"), true)));
        when(balances.findTotalQuantities(actor.restaurantId(), List.of(id))).thenReturn(List.of(quantity));

        service.recalculate(actor, document, List.of(line(id)), now, "127.0.0.1");

        var order = inOrder(costs, balances);
        order.verify(costs).lockReferenceCostStates(actor.restaurantId(), List.of(id));
        order.verify(balances).findTotalQuantities(actor.restaurantId(), List.of(id));
        order.verify(costs).updateReferenceCosts(actor, document.getId(),
                Map.of(id, new BigDecimal("110.00")), now, "127.0.0.1");
    }

    @Test
    void missingSparseBalanceMeansZeroQuantityNotPreviousReferenceCostWeight() {
        UUID id = UUID.randomUUID();
        when(costs.lockReferenceCostStates(actor.restaurantId(), List.of(id)))
                .thenReturn(Map.of(id, new IngredientReferenceCostWriter.ReferenceCost(new BigDecimal("999"), true)));
        when(balances.findTotalQuantities(actor.restaurantId(), List.of(id))).thenReturn(List.of());
        StockDocument document = document(StockDocumentType.RECEIPT);
        service.recalculate(actor, document, List.of(line(id)), now, null);
        UUID documentId = document.getId();
        verify(costs).updateReferenceCosts(actor, documentId,
                Map.of(id, new BigDecimal("130.00")), now, null);
    }

    @ParameterizedTest
    @EnumSource(value = StockDocumentType.class, names = {"ISSUE", "ADJUSTMENT"})
    void nonReceiptDoesNotReadOrWriteAnyReferenceCosts(StockDocumentType type) {
        service.recalculate(actor, document(type), List.of(), now, null);
        verifyNoInteractions(balances, costs);
    }

    private static StockDocument document(StockDocumentType type) {
        StockDocument document = mock(StockDocument.class);
        when(document.getDocumentType()).thenReturn(type);
        when(document.getId()).thenReturn(UUID.randomUUID());
        return document;
    }

    private static StockDocumentItem line(UUID id) {
        StockDocumentItem item = mock(StockDocumentItem.class);
        when(item.getIngredientId()).thenReturn(id);
        when(item.getQuantity()).thenReturn(new BigDecimal("5"));
        when(item.getUnitCost()).thenReturn(new BigDecimal("130"));
        return item;
    }
}
