package com.possaas.modules.inventory.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog;
import com.possaas.modules.inventory.entity.InventoryBalance;
import com.possaas.modules.inventory.entity.InventoryTransaction;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentType;
import com.possaas.modules.inventory.repository.InventoryBalanceRepository;
import com.possaas.modules.inventory.repository.InventoryTransactionWriteRepository;
import com.possaas.modules.inventory.repository.InventoryReservationRepository;
import com.possaas.modules.inventory.service.strategy.StockMovementStrategyRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class StockPostingService {
    private static final BigDecimal ZERO = new BigDecimal("0.000");

    private final TrackedIngredientCatalog ingredients;
    private final InventoryBalanceRepository balances;
    private final InventoryTransactionWriteRepository transactions;
    private final InventoryReservationRepository reservations;
    private final StockMovementStrategyRegistry strategies;
    private final StockDocumentItemFactory itemPolicy;
    private final ReceiptReferenceCostService referenceCosts;
    private final InventoryAvailabilityPolicy availability;
    private final Clock clock;

    public void post(CurrentUser actor, StockDocument document, List<StockDocumentItem> originalItems,
            String ipAddress) {
        List<StockDocumentItem> items = originalItems.stream()
                .sorted(Comparator.comparing(item -> item.getIngredientId().toString()))
                .toList();
        List<UUID> ingredientIds = items.stream().map(StockDocumentItem::getIngredientId).toList();
        var snapshots = ingredients.requireTrackedBatch(actor.restaurantId(), ingredientIds);
        for (StockDocumentItem item : items) {
            var snapshot = snapshots.get(item.getIngredientId());
            itemPolicy.requireIngredientAllowed(document.getDocumentType(), snapshot);
            if (!item.getUnit().equals(snapshot.baseUnit()))
                throw new BusinessException(HttpStatus.CONFLICT, "INGREDIENT_UNIT_CHANGED",
                        "Ingredient base unit changed after the draft was created");
        }

        var timestamp = clock.instant();
        referenceCosts.recalculate(actor, document, items, timestamp, ipAddress);
        Map<UUID, InventoryBalance> current = new HashMap<>();
        balances.findAllForUpdate(actor.restaurantId(), document.getWarehouseId(), ingredientIds)
                .forEach(balance -> current.put(balance.getItemId(), balance));
        Map<UUID, BigDecimal> actualReserved = new HashMap<>();
        reservations.sumReserved(actor.restaurantId(), List.of(document.getWarehouseId()), ingredientIds)
                .forEach(total -> actualReserved.put(total.getIngredientId(), total.getQuantity()));
        for (UUID ingredientId : ingredientIds) {
            InventoryBalance balance = current.get(ingredientId);
            BigDecimal reserved = actualReserved.getOrDefault(ingredientId, ZERO);
            if (balance == null) {
                if (reserved.signum() != 0) throw InventoryAvailabilityPolicy.invariant();
            } else availability.invariant(balance, reserved);
        }
        var strategy = strategies.get(document.getDocumentType());
        strategy.validateDocument(document);
        List<InventoryBalance> changed = new java.util.ArrayList<>();
        List<InventoryTransaction> ledger = new java.util.ArrayList<>();

        for (StockDocumentItem item : items) {
            InventoryBalance balance = current.get(item.getIngredientId());
            BigDecimal before = balance == null ? ZERO : balance.getQuantity().setScale(3);
            BigDecimal delta = strategy.calculateQuantityChange(item).setScale(3);
            BigDecimal after = before.add(delta).setScale(3);
            availability.requireStockPosting(after, balance == null ? ZERO : balance.getReservedQuantity());
            if (balance == null) {
                balance = new InventoryBalance();
                balance.initialize(actor.restaurantId(), document.getWarehouseId(), item.getIngredientId(),
                        ZERO, timestamp);
                current.put(item.getIngredientId(), balance);
            }
            balance.applyQuantityChange(delta, timestamp);
            changed.add(balance);
            ledger.add(new InventoryTransaction(actor.restaurantId(), document.getWarehouseId(),
                    item.getIngredientId(), strategy.transactionType(item), document.getId(), item.getId(),
                    delta, before, after, item.getUnit(), item.getUnitCost(),
                    "STOCK_DOCUMENT:" + document.getId() + ":ITEM:" + item.getId(), actor.userId(),
                    timestamp));
        }
        balances.saveAllAndFlush(changed);
        ledger.forEach(transactions::append);
        transactions.flush();
    }
}
