package com.possaas.modules.menu.service;

import com.possaas.modules.menu.entity.*;
import org.springframework.stereotype.Component;

@Component
public class MenuVisibilityPolicy {
    public boolean sellable(Item item, ItemGroup group) {
        return item.getDeletedAt() == null && item.isActive()
                && item.getAvailabilityStatus() == AvailabilityStatus.AVAILABLE
                && (item.getGroupId() == null || group != null && group.isActive() && group.getDeletedAt() == null);
    }
}
