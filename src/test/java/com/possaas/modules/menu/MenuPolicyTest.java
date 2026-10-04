package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.service.*;
import com.possaas.modules.menu.service.strategy.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MenuPolicyTest {
    private final MenuItemValidationPolicy validation = new MenuItemValidationPolicy();

    private ItemCreationContext context(boolean active) {
        return new ItemCreationContext(UUID.randomUUID(), null, " coffee ", " Coffee ", " cup ", null, null,
                new BigDecimal("0"), active, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void factoryDelegatesAndInitializesSafeDefaults() {
        var strategy = spy(new MenuItemCreationStrategy(validation));
        var factory = new MenuItemFactory(new ItemCreationStrategyRegistry(List.of(strategy)));
        var input = context(false);
        var item = factory.create(ItemType.MENU_ITEM, input);
        verify(strategy).create(input);
        assertThat(item.getItemType()).isEqualTo(ItemType.MENU_ITEM);
        assertThat(item.getSku()).isEqualTo("COFFEE");
        assertThat(item.isActive()).isFalse();
        assertThat(item.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(item.getCostPrice()).isEqualByComparingTo("0");
        assertThat(item.isTrackInventory()).isFalse();
        assertThat(item.getMetadata()).isEmpty();
        assertThat(item.getCreatedAt()).isEqualTo(input.now());
        assertThat(item.getId()).isNull();
    }

    @Test
    void registryRejectsDuplicateMissingAndUnsupportedTypes() {
        var s = new MenuItemCreationStrategy(validation);
        assertThatThrownBy(() -> new ItemCreationStrategyRegistry(List.of(s, s)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ItemCreationStrategyRegistry(List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ItemCreationStrategyRegistry(List.of(s)).get(ItemType.INGREDIENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryCanSelectAnotherImplementationWithoutFactoryBranches() {
        var ingredient = mock(ItemCreationStrategy.class);
        when(ingredient.supportedType()).thenReturn(ItemType.INGREDIENT);
        var expected = new Item();
        var c = context(true);
        when(ingredient.create(c)).thenReturn(expected);
        var f = new MenuItemFactory(
                new ItemCreationStrategyRegistry(List.of(new MenuItemCreationStrategy(validation), ingredient)));
        assertThat(f.create(ItemType.INGREDIENT, c)).isSameAs(expected);
    }

    @Test
    void validatesMoneyAndUrlsWithoutRounding() {
        for (String price : List.of("-1", "0.001", "1000000000000"))
            assertThatThrownBy(() -> validation.price(new BigDecimal(price))).isInstanceOf(BusinessException.class);
        assertThat(validation.price(new BigDecimal("1"))).isEqualTo(new BigDecimal("1.00"));
        for (String url : List.of("file:///test", "https://user:pass@example.com", "bad"))
            assertThatThrownBy(() -> validation.image(url)).isInstanceOf(BusinessException.class);
        assertThat(validation.sku(" ")).isNull();
        assertThat(validation.image(" ")).isNull();
        assertThatThrownBy(() -> validation.required(" ", 30)).isInstanceOf(BusinessException.class);
    }

    @Test
    void lifecycleAndVisibilityAreIndependentOfGroupState() {
        var item = new MenuItemCreationStrategy(validation).create(context(true));
        var policy = new MenuVisibilityPolicy();
        assertThat(policy.sellable(item, null)).isTrue();
        var group = new ItemGroup();
        group.setId(UUID.randomUUID());
        item.setGroupId(group.getId());
        group.setActive(false);
        assertThat(policy.sellable(item, group)).isFalse();
        assertThat(item.isActive()).isTrue();
        group.setActive(true);
        item.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK);
        assertThat(policy.sellable(item, group)).isFalse();
        item.setActive(false);
        assertThatThrownBy(() -> new MenuItemLifecyclePolicy().requireAvailabilityChange(item))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new MenuItemLifecyclePolicy().requireVersion(item, 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void lifecyclePreservesAvailabilityAndDeletionTimestamp() {
        var item = new MenuItemCreationStrategy(validation).create(context(true));
        var policy = new MenuItemLifecyclePolicy();
        policy.changeAvailability(item, AvailabilityStatus.OUT_OF_STOCK);
        policy.changeActive(item, false);
        policy.changeActive(item, true);
        assertThat(item.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.OUT_OF_STOCK);
        var now = Instant.parse("2026-01-02T00:00:00Z");
        policy.softDelete(item, now);
        policy.softDelete(item, now.plusSeconds(1));
        assertThat(item.getDeletedAt()).isEqualTo(now);
        assertThatThrownBy(() -> policy.changeActive(item, true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.changeAvailability(item, AvailabilityStatus.AVAILABLE))
                .isInstanceOf(BusinessException.class);
    }
}
