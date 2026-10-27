package com.possaas.modules.menu.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.menu.entity.Item;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IngredientAudit {
    private final AuditService auditService;

    public Map<String, Object> snapshot(Item ingredient) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("itemType", ingredient.getItemType());
        snapshot.put("sku", ingredient.getSku());
        snapshot.put("name", ingredient.getName());
        snapshot.put("baseUnit", ingredient.getBaseUnit());
        snapshot.put("description", ingredient.getDescription());
        snapshot.put("costPrice", ingredient.getCostPrice());
        snapshot.put("costConfigured", ingredient.isCostConfigured());
        snapshot.put("trackInventory", ingredient.isTrackInventory());
        snapshot.put("active", ingredient.isActive());
        snapshot.put("deletedAt", ingredient.getDeletedAt() == null ? null : ingredient.getDeletedAt().toString());
        return snapshot;
    }

    public void record(CurrentUser actor, String action, Item ingredient, Map<String, Object> before,
            Map<String, Object> after, String ipAddress) {
        auditService.record(actor.restaurantId(), actor.userId(), action, "items", ingredient.getId(), before, after,
                ipAddress);
    }
}
