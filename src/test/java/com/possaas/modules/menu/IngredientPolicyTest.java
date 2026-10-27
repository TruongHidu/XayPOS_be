package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.menu.entity.AvailabilityStatus;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.service.IngredientLifecyclePolicy;
import com.possaas.modules.menu.service.MenuItemValidationPolicy;
import com.possaas.modules.menu.service.strategy.IngredientCreationStrategy;
import com.possaas.modules.menu.service.strategy.ItemCreationContext;
import com.possaas.modules.menu.service.strategy.ItemCreationStrategyRegistry;
import com.possaas.modules.menu.service.strategy.MenuItemCreationStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngredientPolicyTest {
    private final MenuItemValidationPolicy validation = new MenuItemValidationPolicy();

    private ItemCreationContext context(boolean trackInventory) {
        return new ItemCreationContext(UUID.randomUUID(), UUID.randomUUID(), " ingredient ", " Ingredient ", " kg ",
                " description ", "https://example.com/ignored.jpg", new BigDecimal("999"), new BigDecimal("12.50"),
                trackInventory, true, Instant.parse("2026-10-16T08:00:00Z"), true);
    }

    @Test
    void strategyClosesIngredientInvariants() {
        var context = context(true);
        var ingredient = new IngredientCreationStrategy(validation).create(context);
        assertThat(ingredient.getItemType()).isEqualTo(ItemType.INGREDIENT);
        assertThat(ingredient.getGroupId()).isNull();
        assertThat(ingredient.getSalePrice()).isEqualByComparingTo("0.00");
        assertThat(ingredient.getImageUrl()).isNull();
        assertThat(ingredient.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(ingredient.getCostPrice()).isEqualByComparingTo("12.50");
        assertThat(ingredient.isTrackInventory()).isTrue();
        assertThat(ingredient.getMetadata()).isEmpty();
        assertThat(ingredient.getCreatedAt()).isEqualTo(context.now());
        assertThat(ingredient.getUpdatedAt()).isEqualTo(context.now());
    }

    @Test
    void registryRequiresBothStrategiesAndRejectsDuplicates() {
        var menu = new MenuItemCreationStrategy(validation);
        var ingredient = new IngredientCreationStrategy(validation);
        assertThat(new ItemCreationStrategyRegistry(List.of(menu, ingredient)).get(ItemType.INGREDIENT))
                .isSameAs(ingredient);
        assertThatThrownBy(() -> new ItemCreationStrategyRegistry(List.of(menu)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INGREDIENT");
        assertThatThrownBy(() -> new ItemCreationStrategyRegistry(List.of(ingredient)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MENU_ITEM");
        assertThatThrownBy(() -> new ItemCreationStrategyRegistry(List.of(menu, ingredient, ingredient)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void validationRejectsInvalidCostAndLifecycleRejectsStaleVersion() {
        for (String cost : List.of("-1", "1.001", "1000000000000"))
            assertThatThrownBy(() -> validation.price(new BigDecimal(cost))).isInstanceOf(BusinessException.class);
        var ingredient = new IngredientCreationStrategy(validation).create(context(false));
        var lifecycle = new IngredientLifecyclePolicy();
        assertThatThrownBy(() -> lifecycle.requireVersion(ingredient, 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("reload");
        lifecycle.changeActive(ingredient, true);
        assertThat(ingredient.isActive()).isTrue();
    }
}
