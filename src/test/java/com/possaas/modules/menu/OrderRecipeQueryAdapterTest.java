package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.menu.service.OrderRecipeQueryAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderRecipeQueryAdapterTest {
    final RecipeRepository recipes=mock(RecipeRepository.class);
    final RecipeItemRepository details=mock(RecipeItemRepository.class);
    final ItemRepository items=mock(ItemRepository.class);
    final OrderRecipeQueryAdapter adapter=new OrderRecipeQueryAdapter(recipes,details,items);
    final UUID tenant=UUID.randomUUID(),menuId=UUID.randomUUID();
    @Test void configurationReadIncludesDisabledItemsWithoutConsultingRecipeDefinitions() {
        var disabled=new Item();disabled.initialize(tenant,Instant.EPOCH);disabled.setId(menuId);
        disabled.setItemType(ItemType.MENU_ITEM);disabled.setRecipeEnabled(false);
        var enabled=new Item();enabled.initialize(tenant,Instant.EPOCH);enabled.setId(UUID.randomUUID());
        enabled.setItemType(ItemType.MENU_ITEM);enabled.setRecipeEnabled(true);
        when(items.findAllByRestaurantIdAndIdIn(eq(tenant),anyCollection())).thenReturn(List.of(disabled,enabled));
        var ids=List.of(disabled.getId(),enabled.getId());
        var result=adapter.findRecipeSettings(tenant,ids);
        assertThat(result).containsEntry(menuId,false).containsEntry(enabled.getId(),true);
        verify(items,times(1)).findAllByRestaurantIdAndIdIn(tenant,ids);
        verifyNoInteractions(recipes,details);
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void noActiveRecipeDoesNotInventAnEmptyRecipeOrReadIngredients() {
        when(recipes.findAllByRestaurantIdAndMenuItemIdInAndActiveTrue(eq(tenant),anyCollection())).thenReturn(List.of());
        assertThat(adapter.findActive(tenant,List.of(menuId))).isEmpty();verifyNoInteractions(details,items);
    }
    @Test void snapshotsRecipeNamesButCurrentTrackingFlagUsingBatchQueries() {
        var ingredient=fixture();ingredient.setName("New ingredient name");ingredient.setSku("NEW");ingredient.setTrackInventory(false);
        var result=adapter.findActive(tenant,List.of(menuId));var value=result.get(menuId);
        assertThat(value.version()).isEqualTo(7);assertThat(value.ingredients().getFirst().name()).isEqualTo("Original ingredient");
        assertThat(value.ingredients().getFirst().trackInventory()).isFalse();
        verify(recipes,times(1)).findAllByRestaurantIdAndMenuItemIdInAndActiveTrue(eq(tenant),anyCollection());
        verify(details,times(1)).findAllByRestaurantIdAndRecipeIdInOrderByRecipeIdAscIngredientIdAsc(eq(tenant),anyCollection());
        verify(items,times(1)).findAllByRestaurantIdAndIdIn(eq(tenant),anyCollection());
        assertThatThrownBy(()->value.ingredients().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest @ValueSource(strings={"inactive","deleted","type","unit"})
    void invalidLiveIngredientCannotSilentlyBecomeNoRecipe(String state) {
        var ingredient=fixture();
        switch(state){case "inactive"->ingredient.setActive(false);case "deleted"->ingredient.setDeletedAt(Instant.EPOCH);
            case "type"->ingredient.setItemType(ItemType.MENU_ITEM);case "unit"->ingredient.setBaseUnit("kg");}
        assertThatThrownBy(()->adapter.findActive(tenant,List.of(menuId))).isInstanceOfSatisfying(ConflictException.class,
            e->assertThat(e.getCode()).isEqualTo(state.equals("unit")?"RECIPE_INGREDIENT_UNIT_MISMATCH":"RECIPE_INGREDIENT_UNAVAILABLE"));
    }
    Item fixture(){
        var ingredient=new Item();ingredient.initialize(tenant,Instant.EPOCH);ingredient.setId(UUID.randomUUID());ingredient.setName("Original ingredient");
        ingredient.setItemType(ItemType.INGREDIENT);ingredient.setBaseUnit("g");ingredient.setTrackInventory(true);
        var recipe=new Recipe();recipe.initialize(tenant,menuId,7,BigDecimal.TEN,"portion",Instant.EPOCH);recipe.setId(UUID.randomUUID());
        var line=new RecipeItem();line.initialize(tenant,recipe.getId(),ingredient,BigDecimal.TEN,BigDecimal.ZERO,Instant.EPOCH);
        when(recipes.findAllByRestaurantIdAndMenuItemIdInAndActiveTrue(eq(tenant),anyCollection())).thenReturn(List.of(recipe));
        when(details.findAllByRestaurantIdAndRecipeIdInOrderByRecipeIdAscIngredientIdAsc(eq(tenant),anyCollection())).thenReturn(List.of(line));
        when(items.findAllByRestaurantIdAndIdIn(eq(tenant),anyCollection())).thenReturn(List.of(ingredient));return ingredient;
    }
}
