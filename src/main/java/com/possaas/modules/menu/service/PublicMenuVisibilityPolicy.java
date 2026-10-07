package com.possaas.modules.menu.service;

import com.possaas.modules.menu.entity.AvailabilityStatus;
import com.possaas.modules.menu.entity.ItemGroup;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PublicMenuVisibilityPolicy {
    public Set<AvailabilityStatus> visibleStatuses() {
        return Set.of(AvailabilityStatus.AVAILABLE, AvailabilityStatus.OUT_OF_STOCK);
    }

    public boolean visibleGroup(ItemGroup group, UUID restaurantId) {
        return group != null && restaurantId.equals(group.getRestaurantId())
                && group.isActive() && group.getDeletedAt() == null;
    }
}
