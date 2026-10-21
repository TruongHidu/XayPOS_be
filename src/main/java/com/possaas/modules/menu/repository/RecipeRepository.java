package com.possaas.modules.menu.repository;

import com.possaas.modules.menu.entity.Recipe;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipeRepository extends JpaRepository<Recipe, UUID> {
    List<Recipe> findAllByRestaurantIdAndMenuItemIdInAndActiveTrue(UUID restaurantId, Collection<UUID> menuItemIds);
    Optional<Recipe> findByIdAndRestaurantIdAndMenuItemId(UUID id, UUID restaurantId, UUID menuItemId);

    Optional<Recipe> findByRestaurantIdAndMenuItemIdAndActiveTrue(UUID restaurantId, UUID menuItemId);

    Page<Recipe> findAllByRestaurantIdAndMenuItemId(UUID restaurantId, UUID menuItemId, Pageable pageable);

    boolean existsByRestaurantIdAndMenuItemIdAndActiveTrue(UUID restaurantId, UUID menuItemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Recipe r where r.id=:recipeId and r.restaurantId=:restaurantId "
            + "and r.menuItemId=:menuItemId")
    Optional<Recipe> findByIdForUpdate(@Param("restaurantId") UUID restaurantId,
            @Param("menuItemId") UUID menuItemId, @Param("recipeId") UUID recipeId);

    @Query("select coalesce(max(r.recipeVersion),0) from Recipe r "
            + "where r.restaurantId=:restaurantId and r.menuItemId=:menuItemId")
    int findMaxRecipeVersion(@Param("restaurantId") UUID restaurantId,
            @Param("menuItemId") UUID menuItemId);
}
