package com.possaas.modules.menu.service;

import com.possaas.common.security.*;
import com.possaas.common.exception.*;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MenuAccessService {
    private final CurrentTenantProvider tenant;
    private final CurrentUserProvider user;
    private final FeatureAccessChecker features;
    private final RestaurantRepository restaurants;
    private final ItemRepository items;
    private final ItemGroupRepository groups;

    public CurrentUser require(boolean mutation) {
        UUID id = tenant.getRequiredRestaurantId();
        if (mutation)
            restaurants.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        features.requireFeature(id, "MENU_MANAGEMENT");
        return user.getRequired();
    }

    public Item item(UUID tenantId, UUID id, boolean includeDeleted) {
        return items.findByIdAndRestaurantIdAndItemType(id, tenantId, ItemType.MENU_ITEM)
                .filter(i -> includeDeleted || i.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("MENU_ITEM_NOT_FOUND", "Menu item not found"));
    }

    public ItemGroup group(UUID tenantId, UUID id, boolean includeDeleted) {
        return groups.findByIdAndRestaurantId(id, tenantId).filter(g -> includeDeleted || g.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("MENU_GROUP_NOT_FOUND", "Menu group not found"));
    }

    public ItemGroup assignableGroup(UUID tenantId, UUID id) {
        if (id == null)
            return null;
        var group = group(tenantId, id, false);
        if (!group.isActive())
            throw new ConflictException("MENU_GROUP_INACTIVE", "Menu group is inactive");
        return group;
    }
}
