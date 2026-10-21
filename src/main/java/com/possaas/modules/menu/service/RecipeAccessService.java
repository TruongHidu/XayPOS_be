package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.repository.RecipeRepository;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecipeAccessService {
    private final CurrentTenantProvider tenantProvider;
    private final CurrentUserProvider userProvider;
    private final FeatureAccessChecker features;
    private final RestaurantRepository restaurants;
    private final ItemRepository items;
    private final RecipeRepository recipes;

    public CurrentUser require(boolean mutation) {
        UUID restaurantId = tenantProvider.getRequiredRestaurantId();
        if (mutation)
            restaurants.findByIdForUpdate(restaurantId)
                    .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        features.requireFeature(restaurantId, "RECIPE_MANAGEMENT");
        return userProvider.getRequired();
    }

    public Item menuItem(UUID restaurantId, UUID menuItemId) {
        return items.findByIdAndRestaurantIdAndItemType(menuItemId, restaurantId, ItemType.MENU_ITEM)
                .filter(item -> item.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("MENU_ITEM_NOT_FOUND", "Menu item not found"));
    }

    public Recipe recipe(UUID restaurantId, UUID menuItemId, UUID recipeId) {
        return recipes.findByIdAndRestaurantIdAndMenuItemId(recipeId, restaurantId, menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("RECIPE_NOT_FOUND", "Recipe not found"));
    }

    public Recipe recipeForUpdate(UUID restaurantId, UUID menuItemId, UUID recipeId) {
        return recipes.findByIdForUpdate(restaurantId, menuItemId, recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("RECIPE_NOT_FOUND", "Recipe not found"));
    }

    public Recipe activeRecipe(UUID restaurantId, UUID menuItemId) {
        return recipes.findByRestaurantIdAndMenuItemIdAndActiveTrue(restaurantId, menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ACTIVE_RECIPE_NOT_FOUND",
                        "Active recipe not found"));
    }

    public void requireNoActiveRecipe(UUID restaurantId, UUID menuItemId) {
        if (recipes.existsByRestaurantIdAndMenuItemIdAndActiveTrue(restaurantId, menuItemId))
            throw new ConflictException("ACTIVE_RECIPE_ALREADY_EXISTS",
                    "Menu item already has an active recipe; create a new version instead");
    }
}
