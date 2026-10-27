package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.IngredientReferenceCostWriter;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentType;
import com.possaas.modules.inventory.repository.InventoryBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ReceiptReferenceCostService {
    private final InventoryBalanceRepository balances;
    private final IngredientReferenceCostWriter costs;
    private final WeightedAverageCostPolicy policy;
    private final com.possaas.modules.audit.service.AuditService audit;

    /** Run before changing any balances, under the existing restaurant mutation lock. */
    public void recalculate(CurrentUser actor, StockDocument document, List<StockDocumentItem> items,
            Instant timestamp, String ipAddress) {
        if (document.getDocumentType() != StockDocumentType.RECEIPT) return;
        List<UUID> ids = items.stream().map(StockDocumentItem::getIngredientId).toList();
        var previousCosts = costs.lockReferenceCostStates(actor.restaurantId(), ids);
        Map<UUID, BigDecimal> previousQuantities = balances.findTotalQuantities(actor.restaurantId(), ids)
                .stream().collect(Collectors.toMap(InventoryBalanceRepository.IngredientTotalQuantity::getItemId,
                        InventoryBalanceRepository.IngredientTotalQuantity::getQuantity));
        Map<UUID, BigDecimal> updated = new LinkedHashMap<>();
        for (StockDocumentItem item : items) {
            UUID id = item.getIngredientId();
            BigDecimal quantity = previousQuantities.getOrDefault(id, BigDecimal.ZERO);
            var previous = previousCosts.get(id);
            if (previous == null)
                throw new com.possaas.common.exception.ConflictException("INVALID_INVENTORY_COST_DATA", "Reference cost state is missing");
            if (quantity.signum() > 0 && !previous.configured()) {
                audit.record(actor.restaurantId(), actor.userId(), "INGREDIENT_COST_RECALCULATION_SKIPPED", "items", id,
                        null, Map.of("sourceDocumentId", document.getId(), "reason", "UNCONFIGURED_OPENING_REFERENCE_COST"), ipAddress);
                continue;
            }
            updated.put(id, policy.calculate(quantity, previous.price(), item.getQuantity(), item.getUnitCost()));
        }
        if (!updated.isEmpty()) costs.updateReferenceCosts(actor, document.getId(), updated, timestamp, ipAddress);
    }
}
