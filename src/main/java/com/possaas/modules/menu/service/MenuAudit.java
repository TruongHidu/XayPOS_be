package com.possaas.modules.menu.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.menu.entity.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MenuAudit {
    private final AuditService audit;

    public Map<String, Object> snapshot(ItemGroup g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.getName());
        m.put("displayOrder", g.getDisplayOrder());
        m.put("active", g.isActive());
        m.put("deletedAt", g.getDeletedAt() == null ? null : g.getDeletedAt().toString());
        return m;
    }

    public Map<String, Object> snapshot(Item i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupId", i.getGroupId());
        m.put("name", i.getName());
        m.put("sku", i.getSku());
        m.put("baseUnit", i.getBaseUnit());
        m.put("description", i.getDescription());
        m.put("imageUrl", i.getImageUrl());
        m.put("salePrice", i.getSalePrice());
        m.put("active", i.isActive());
        m.put("availabilityStatus", i.getAvailabilityStatus());
        m.put("deletedAt", i.getDeletedAt() == null ? null : i.getDeletedAt().toString());
        return m;
    }

    public void record(CurrentUser actor, String action, MenuRecord record, Map<String, Object> before,
            Map<String, Object> after, String ip) {
        audit.record(actor.restaurantId(), actor.userId(), action, record instanceof Item ? "items" : "item_groups",
                record.getId(), before, after, ip);
    }
}
