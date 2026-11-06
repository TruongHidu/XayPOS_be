package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.repository.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class StockAuditConfirmationOperation {
    private final InventoryAccessService access;
    private final TrackedIngredientCatalog ingredients;
    private final StockAuditItemRepository items;
    private final InventoryBalanceRepository balances;
    private final InventoryReservationRepository reservations;
    private final StockAuditMovementQuery movements;
    private final InventoryAvailabilityPolicy availability;
    private final StockAuditAdjustmentOperation adjustment;

    public StockDocument confirm(CurrentUser actor,StockAudit audit,String ip){
        var warehouse=access.warehouseIncludingDeleted(actor.restaurantId(),audit.getWarehouseId());
        if(!warehouse.isActive() || warehouse.getDeletedAt()!=null)
            throw StockAuditPolicy.conflict("WAREHOUSE_INACTIVE","Warehouse is inactive or deleted");
        var rows=items.findAllByRestaurantIdAndStockAuditIdOrderByIngredientId(actor.restaurantId(),audit.getId());
        if(rows.isEmpty())throw StockAuditPolicy.conflict("STOCK_AUDIT_EMPTY","Stock audit has no snapshot");
        if(rows.stream().anyMatch(row->row.getCountedQuantity()==null))
            throw StockAuditPolicy.conflict("STOCK_AUDIT_INCOMPLETE","Count every snapshot ingredient before confirming");
        var ids=rows.stream().map(StockAuditItem::getIngredientId).toList();
        Map<UUID,TrackedIngredientCatalog.TrackedIngredientSnapshot> current;
        try{current=ingredients.requireTrackedBatch(actor.restaurantId(),ids);}
        catch(ResourceNotFoundException changed){throw StockAuditPolicy.stale();}
        var physical=balances.findAllForUpdate(actor.restaurantId(),audit.getWarehouseId(),ids).stream()
            .collect(Collectors.toMap(InventoryBalance::getItemId,Function.identity()));
        var tokens=movements.counts(actor.restaurantId(),audit.getWarehouseId(),ids);
        var reserved=reservations.sumReserved(actor.restaurantId(),List.of(audit.getWarehouseId()),ids).stream()
            .collect(Collectors.toMap(InventoryReservationRepository.ReservedQuantity::getIngredientId,
                InventoryReservationRepository.ReservedQuantity::getQuantity));
        for(var row:rows){
            var id=row.getIngredientId();var balance=physical.get(id);var actual=reserved.getOrDefault(id,BigDecimal.ZERO);
            BigDecimal quantity=balance==null?BigDecimal.ZERO:balance.getQuantity();
            if(!current.get(id).baseUnit().equals(row.getUnit()) || quantity.compareTo(row.getSystemQuantity())!=0
                || tokens.getOrDefault(id,0L)!=row.getMovementCountSnapshot())throw StockAuditPolicy.stale();
            if(balance==null){if(actual.signum()!=0)throw InventoryAvailabilityPolicy.invariant();}
            else availability.invariant(balance,actual);
            availability.requireStockPosting(row.getCountedQuantity(),balance==null?BigDecimal.ZERO:balance.getReservedQuantity());
        }
        return adjustment.post(actor,audit,rows,ip);
    }
}
