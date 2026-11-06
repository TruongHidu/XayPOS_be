package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.entity.*;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class MenuItemLifecyclePolicy {
    public void changeActive(Item item, boolean active) {
        requireNotDeleted(item);
        // Availability is deliberately preserved across disable/enable.
        item.setActive(active);
    }

    public void changeAvailability(Item item, AvailabilityStatus status) {
        requireAvailabilityChange(item);
        item.setAvailabilityStatus(status);
    }

    public void softDelete(Item item, Instant now) {
        if (item.getDeletedAt() == null)
            item.setDeletedAt(now);
    }

    private void requireNotDeleted(Item item) {
        if (item.getDeletedAt() != null)
            throw new ConflictException("INVALID_MENU_ITEM_TRANSITION", "Deleted items cannot be changed");
    }

    public void requireAvailabilityChange(Item item) {
        if (item.getDeletedAt() != null || !item.isActive())
            throw new ConflictException("INVALID_MENU_ITEM_TRANSITION", "Only active items can change availability");
    }

    public void requireVersion(MenuRecord record, long expectedVersion) {
        if (record.getVersion() != expectedVersion)
            throw new ConflictException("CONCURRENT_MENU_UPDATE", "Menu resource changed; reload before retrying");
    }
}
