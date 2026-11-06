package com.possaas.modules.menu.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.menu.dto.RecipeResponse;
import com.possaas.modules.menu.dto.RecipeSearch;
import com.possaas.modules.menu.dto.RecipeSummaryResponse;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.repository.RecipeItemRepository;
import com.possaas.modules.menu.repository.RecipeRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipeQueryService {
    private final RecipeAccessService access;
    private final RecipeRepository recipes;
    private final RecipeItemRepository recipeItems;
    private final RecipeResponseMapper mapper;

    public RecipeResponse active(UUID menuItemId) {
        CurrentUser actor = access.require(false);
        access.menuItem(actor.restaurantId(), menuItemId);
        Recipe recipe = access.activeRecipe(actor.restaurantId(), menuItemId);
        return detail(actor.restaurantId(), recipe);
    }

    public RecipeResponse detail(UUID menuItemId, UUID recipeId) {
        CurrentUser actor = access.require(false);
        access.menuItem(actor.restaurantId(), menuItemId);
        return detail(actor.restaurantId(), access.recipe(actor.restaurantId(), menuItemId, recipeId));
    }

    public PageResponse<RecipeSummaryResponse> history(UUID menuItemId, RecipeSearch search) {
        CurrentUser actor = access.require(false);
        access.menuItem(actor.restaurantId(), menuItemId);
        var page = recipes.findAllByRestaurantIdAndMenuItemId(actor.restaurantId(), menuItemId, search.pageable());
        Map<UUID, Long> counts = new HashMap<>();
        if (!page.isEmpty()) {
            var ids = page.getContent().stream().map(Recipe::getId).toList();
            recipeItems.countByRecipeIds(actor.restaurantId(), ids)
                    .forEach(count -> counts.put(count.getRecipeId(), count.getIngredientCount()));
        }
        return PageResponse.from(page.map(recipe -> mapper.summary(recipe, counts.getOrDefault(recipe.getId(), 0L))));
    }

    private RecipeResponse detail(UUID restaurantId, Recipe recipe) {
        return mapper.detail(recipe,
                recipeItems.findAllByRestaurantIdAndRecipeIdOrderByIngredientId(restaurantId, recipe.getId()));
    }
}
