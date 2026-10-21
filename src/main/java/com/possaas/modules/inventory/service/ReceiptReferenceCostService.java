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

    /** Run before changing any balances, under the existing restaurant mutation lock. */
    public void recalculate(CurrentUser actor, StockDocument document, List<StockDocumentItem> items,
            Instant timestamp, String ipAddress) {
        if (document.getDocumentType() != StockDocumentType.RECEIPT) return;
        List<UUID> ids = items.stream().map(StockDocumentItem::getIngredientId).toList();
        Map<UUID, BigDecimal> previousCosts = costs.lockReferenceCosts(actor.restaurantId(), ids);
        Map<UUID, BigDecimal> previousQuantities = balances.findTotalQuantities(actor.restaurantId(), ids)
                .stream().collect(Collectors.toMap(InventoryBalanceRepository.IngredientTotalQuantity::getItemId,
                        InventoryBalanceRepository.IngredientTotalQuantity::getQuantity));
        Map<UUID, BigDecimal> updated = new LinkedHashMap<>();
        for (StockDocumentItem item : items) {
            UUID id = item.getIngredientId();
            updated.put(id, policy.calculate(previousQuantities.getOrDefault(id, BigDecimal.ZERO),
                    previousCosts.get(id), item.getQuantity(), item.getUnitCost()));
        }
        costs.updateReferenceCosts(actor, document.getId(), updated, timestamp, ipAddress);
    }
}
