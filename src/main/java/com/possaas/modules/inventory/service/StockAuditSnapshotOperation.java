package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.*;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.repository.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class StockAuditSnapshotOperation {
    private final TrackedIngredientCatalog ingredients;
    private final IngredientReferenceCostWriter costs;
    private final InventoryBalanceRepository balances;
    private final StockAuditMovementQuery movements;
    private final StockAuditItemRepository items;
    private final InventoryAvailabilityPolicy quantities;
    private final StockAuditPolicy policy;
    private final Clock clock;

    public void capture(CurrentUser actor,StockAudit audit){
        var scope=ingredients.findAllTracked(actor.restaurantId());
        if(scope.isEmpty())throw StockAuditPolicy.conflict("STOCK_AUDIT_EMPTY","No tracked ingredients are available to count");
        var ids=scope.stream().map(TrackedIngredientCatalog.TrackedIngredientSnapshot::id).toList();
        var prices=costs.lockReferenceCosts(actor.restaurantId(),ids);
        var physical=balances.findAllForUpdate(actor.restaurantId(),audit.getWarehouseId(),ids).stream()
            .collect(Collectors.toMap(InventoryBalance::getItemId,Function.identity()));
        var tokens=movements.counts(actor.restaurantId(),audit.getWarehouseId(),ids);var now=clock.instant();
        items.saveAllAndFlush(scope.stream().map(ingredient->{
            var balance=physical.get(ingredient.id());
            return new StockAuditItem(audit,ingredient,quantities.quantity(balance==null?BigDecimal.ZERO:balance.getQuantity()),
                policy.price(prices.get(ingredient.id())),tokens.getOrDefault(ingredient.id(),0L),now);
        }).toList());
    }
}
