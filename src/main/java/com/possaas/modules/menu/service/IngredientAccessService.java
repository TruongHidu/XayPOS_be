package com.possaas.modules.menu.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IngredientAccessService {
    private static final String RECIPE_MANAGEMENT = "RECIPE_MANAGEMENT";
    private static final String INVENTORY_MANAGEMENT = "INVENTORY_MANAGEMENT";

    private final CurrentTenantProvider tenantProvider;
    private final CurrentUserProvider userProvider;
    private final CurrentEntitlementQuery entitlementQuery;
    private final RestaurantRepository restaurants;
    private final ItemRepository items;

    public CurrentUser require(boolean mutation) {
        UUID restaurantId = tenantProvider.getRequiredRestaurantId();
        if (mutation)
            restaurants.findByIdForUpdate(restaurantId)
                    .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        var entitlement = entitlementQuery.getForRestaurant(restaurantId);
        if (!entitlement.hasFeature(RECIPE_MANAGEMENT) && !entitlement.hasFeature(INVENTORY_MANAGEMENT))
            throw new BusinessException(HttpStatus.FORBIDDEN, "FEATURE_NOT_ENTITLED",
                    "The current package does not include ingredient management");
        return userProvider.getRequired();
    }

    public Item ingredient(UUID restaurantId, UUID ingredientId, boolean includeDeleted) {
        return items.findByIdAndRestaurantIdAndItemType(ingredientId, restaurantId, ItemType.INGREDIENT)
                .filter(item -> includeDeleted || item.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found"));
    }
}
