package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditRecordCommand;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.inventory.entity.OrderItemInventoryAllocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class InventoryAllocationAudit {
    private final AuditService audit;

    public void record(CurrentUser actor, List<OrderItemInventoryAllocation> allocations, String ip) {
        audit.recordAll(allocations.stream().map(allocation -> {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("orderId", allocation.getOrderId()); after.put("orderItemId", allocation.getOrderItemId());
            after.put("allocationId", allocation.getId()); after.put("warehouseId", allocation.getWarehouseId());
            after.put("status", allocation.getStatus()); after.put("skipReason", allocation.getSkipReason());
            return new AuditRecordCommand(actor.restaurantId(), actor.userId(),
                    "ORDER_ITEM_INVENTORY_" + allocation.getStatus().name(),
                    "order_item_inventory_allocations", allocation.getId(), null, after, ip);
        }).toList());
    }
}
