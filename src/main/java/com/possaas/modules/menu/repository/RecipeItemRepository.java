package com.possaas.modules.menu.repository;

import com.possaas.modules.menu.entity.RecipeItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipeItemRepository extends JpaRepository<RecipeItem, UUID> {
    List<RecipeItem> findAllByRestaurantIdAndRecipeIdInOrderByRecipeIdAscIngredientIdAsc(
            UUID restaurantId, Collection<UUID> recipeIds);
    List<RecipeItem> findAllByRestaurantIdAndRecipeIdOrderByIngredientId(UUID restaurantId, UUID recipeId);

    interface RecipeItemCount {
        UUID getRecipeId();

        long getIngredientCount();
    }

    @Query("select ri.recipeId as recipeId, count(ri.id) as ingredientCount from RecipeItem ri "
            + "where ri.restaurantId=:restaurantId and ri.recipeId in :recipeIds group by ri.recipeId")
    List<RecipeItemCount> countByRecipeIds(@Param("restaurantId") UUID restaurantId,
            @Param("recipeIds") Collection<UUID> recipeIds);

    @Query("select case when count(ri.id)>0 then true else false end from RecipeItem ri, Recipe r "
            + "where ri.recipeId=r.id and ri.restaurantId=:restaurantId and r.restaurantId=:restaurantId "
            + "and ri.ingredientId=:ingredientId and r.active=true")
    boolean existsActiveRecipeUsingIngredient(@Param("restaurantId") UUID restaurantId,
            @Param("ingredientId") UUID ingredientId);
}
