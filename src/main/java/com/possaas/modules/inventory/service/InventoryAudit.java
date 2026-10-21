package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.inventory.entity.InventoryBalance;
import com.possaas.modules.inventory.entity.Warehouse;
import com.possaas.modules.inventory.entity.StockDocument;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryAudit {
    private final AuditService audits;

    public Map<String, Object> warehouse(Warehouse warehouse) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", warehouse.getCode());
        value.put("name", warehouse.getName());
        value.put("default", warehouse.isDefaultWarehouse());
        value.put("active", warehouse.isActive());
        value.put("deletedAt", warehouse.getDeletedAt() == null ? null : warehouse.getDeletedAt().toString());
        return value;
    }

    public Map<String, Object> threshold(InventoryBalance balance) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("warehouseId", balance.getWarehouseId());
        value.put("ingredientId", balance.getItemId());
        value.put("minQuantity", balance.getMinQuantity());
        return value;
    }

    public void warehouse(CurrentUser actor, String action, Warehouse warehouse,
            Map<String, Object> before, Map<String, Object> after, String ipAddress) {
        audits.record(actor.restaurantId(), actor.userId(), action, "warehouses", warehouse.getId(),
                before, after, ipAddress);
    }

    public void threshold(CurrentUser actor, InventoryBalance balance, Map<String, Object> before,
            String ipAddress) {
        audits.record(actor.restaurantId(), actor.userId(), "INVENTORY_THRESHOLD_UPDATED",
                "inventory_balances", balance.getId(), before, threshold(balance), ipAddress);
    }

    public Map<String, Object> document(StockDocument document) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("documentCode", document.getDocumentCode());
        value.put("documentType", document.getDocumentType().name());
        value.put("status", document.getStatus().name());
        value.put("warehouseId", document.getWarehouseId());
        value.put("lineCount", document.getLineCount());
        value.put("totalQuantity", document.getTotalQuantity());
        value.put("totalCost", document.getTotalCost());
        return value;
    }

    public void document(CurrentUser actor, String action, StockDocument document,
            Map<String, Object> before, Map<String, Object> after, String ipAddress) {
        audits.record(actor.restaurantId(), actor.userId(), action, "stock_documents", document.getId(),
                before, after, ipAddress);
    }
}
