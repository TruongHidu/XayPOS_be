package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.entity.StockDocumentType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class StockDocumentPermissionPolicy {
    public void require(CurrentUser actor, StockDocumentType type) {
        String permission = switch (type) {
            case RECEIPT -> "INVENTORY_RECEIVE";
            case ISSUE -> "INVENTORY_ISSUE";
            case ADJUSTMENT -> "INVENTORY_ADJUST";
        };
        if (!actor.hasPermission(permission))
            throw new AccessDeniedException("Required inventory document permission is missing");
    }
}
