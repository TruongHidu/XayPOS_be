package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.service.InventoryAvailabilityPolicy;
import com.possaas.modules.inventory.service.InventoryShortageCalculator;
import com.possaas.modules.order.application.port.InsufficientStockException;
import com.possaas.modules.order.application.port.InsufficientStockException.ShortageLine;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.AcceptedItem;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.Usage;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryShortageCalculatorTest {
    private final InventoryShortageCalculator calculator = new InventoryShortageCalculator(new InventoryAvailabilityPolicy());
    private final UUID beef = UUID.randomUUID(), noodle = UUID.randomUUID(), herb = UUID.randomUUID();

    @Test
    void singleLineReportsWholePortionsThatStillFit() {
        var pho = line("Phở bò", "3", "tô", usage(beef, "Thịt bò", "315"));
        var result = shortages(List.of(pho), Map.of(beef, d("300")));
        assertThat(result).singleElement().satisfies(line -> {
            assertThat(line.orderItemId()).isEqualTo(pho.orderItemId());
            assertThat(line.menuItemId()).isEqualTo(pho.menuItemId());
            assertThat(line.itemName()).isEqualTo("Phở bò");
            assertThat(line.unit()).isEqualTo("tô");
            assertThat(line.requestedQuantity()).isEqualByComparingTo("3");
            assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("2");
            assertThat(line.sharesIngredientWithOtherLines()).isFalse();
            assertThat(line.shortIngredients()).singleElement().satisfies(ingredient -> {
                assertThat(ingredient.ingredientId()).isEqualTo(beef);
                assertThat(ingredient.name()).isEqualTo("Thịt bò");
                assertThat(ingredient.unit()).isEqualTo("g");
                assertThat(ingredient.availableQuantity()).isEqualByComparingTo("300");
                assertThat(ingredient.requiredQuantity()).isEqualByComparingTo("315");
            });
        });
    }

    @Test
    void sharedIngredientKeepsOtherLinesUnchangedAndFlagsSharing() {
        var pho = line("Phở bò", "3", "tô", usage(beef, "Thịt bò", "315"));
        var bun = line("Bún bò", "3", "tô", usage(beef, "Thịt bò", "315"));
        var result = shortages(List.of(pho, bun), Map.of(beef, d("500")));
        assertThat(result).extracting(ShortageLine::orderItemId).containsExactly(pho.orderItemId(), bun.orderItemId());
        assertThat(result).allSatisfy(line -> {
            assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("1");
            assertThat(line.sharesIngredientWithOtherLines()).isTrue();
            assertThat(line.shortIngredients()).singleElement().satisfies(ingredient -> {
                assertThat(ingredient.availableQuantity()).isEqualByComparingTo("500");
                assertThat(ingredient.requiredQuantity()).isEqualByComparingTo("630");
            });
        });
    }

    @Test
    void missingBalanceMeansSoldOut() {
        var pho = line("Phở bò", "2", "tô", usage(beef, "Thịt bò", "210"));
        assertThat(shortages(List.of(pho), Map.of())).singleElement()
                .satisfies(line -> assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("0"));
        assertThat(shortages(List.of(pho), Map.of(beef, d("0")))).singleElement()
                .satisfies(line -> assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("0"));
    }

    @Test
    void directStockReportsInMenuUnit() {
        UUID can = UUID.randomUUID();
        var cola = line("Coca lon", "5", "lon", usage(can, "Coca lon", "5", "lon"));
        assertThat(shortages(List.of(cola), Map.of(can, d("3")))).singleElement().satisfies(line -> {
            assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("3");
            assertThat(line.unit()).isEqualTo("lon");
        });
    }

    @Test
    void fractionalOrderQuantityUsesThousandthSteps() {
        UUID rice = UUID.randomUUID();
        var bag = line("Gạo", "1.5", "kg", usage(rice, "Gạo", "1.5", "kg"));
        assertThat(shortages(List.of(bag), Map.of(rice, d("1.234")))).singleElement()
                .satisfies(line -> assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("1.234"));
    }

    @Test
    void roundedRecipeUsageNeverOverstatesSellableQuantity() {
        // 100g recipe with yield 3: one portion is snapshotted as CEILING(33.333...) = 33.334g.
        var cake = line("Bánh", "4", "phần", usage(beef, "Bột", "133.334"));
        var result = shortages(List.of(cake), Map.of(beef, d("100")));
        BigDecimal max = result.get(0).maxAvailableQuantity();
        assertThat(max).isEqualByComparingTo("2");
        BigDecimal perPortion = new BigDecimal("100").divide(new BigDecimal("3"), 10, java.math.RoundingMode.HALF_UP);
        assertThat(perPortion.multiply(max)).isLessThanOrEqualTo(d("100"));
    }

    @Test
    void onlyShortIngredientsAndAffectedLinesAreReported() {
        var pho = line("Phở bò", "2", "tô", usage(beef, "Thịt bò", "210"), usage(noodle, "Bánh phở", "400"));
        var salad = line("Gỏi", "1", "đĩa", usage(herb, "Rau thơm", "50"));
        var result = shortages(List.of(pho, salad), Map.of(beef, d("150"), noodle, d("1000"), herb, d("500")));
        assertThat(result).singleElement().satisfies(line -> {
            assertThat(line.orderItemId()).isEqualTo(pho.orderItemId());
            assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("1");
            assertThat(line.shortIngredients()).extracting(InsufficientStockException.ShortIngredient::ingredientId).containsExactly(beef);
        });
        assertThat(shortages(List.of(salad), Map.of(herb, d("500")))).isEmpty();
    }

    @Test
    void secondIngredientCanLimitBelowTheShortOne() {
        var pho = line("Phở bò", "4", "tô", usage(beef, "Thịt bò", "420"), usage(noodle, "Bánh phở", "800"));
        var result = shortages(List.of(pho), Map.of(beef, d("400"), noodle, d("1000")));
        assertThat(result).singleElement().satisfies(line -> {
            assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("3");
            assertThat(line.shortIngredients()).hasSize(1);
        });
        var tight = shortages(List.of(pho), Map.of(beef, d("400"), noodle, d("450")));
        assertThat(tight.get(0).maxAvailableQuantity()).isEqualByComparingTo("2");
        assertThat(tight.get(0).shortIngredients()).hasSize(2);
    }

    @Test
    void exceptionCarriesDetailsUnderTheLegacyCode() {
        var line = shortages(List.of(line("Phở bò", "1", "tô", usage(beef, "Thịt bò", "105"))), Map.of());
        BusinessException failure = new InsufficientStockException(line);
        assertThat(failure.getCode()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(failure.getStatus().value()).isEqualTo(409);
        assertThat(failure.details()).isInstanceOf(InsufficientStockException.Details.class);
        assertThatThrownBy(() -> new InsufficientStockException(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ingredientDetailIsOmittedWithoutInventoryAccessButMenuEstimateRemains() {
        var pho = line("Phở bò", "3", "tô", usage(beef, "Thịt bò", "315"));
        var bun = line("Bún bò", "3", "tô", usage(beef, "Thịt bò", "315"));
        var result = calculator.shortages(List.of(pho, bun), Map.of(beef, d("500")), false);
        assertThat(result).hasSize(2).allSatisfy(line -> {
            assertThat(line.maxAvailableQuantity()).isEqualByComparingTo("1");
            assertThat(line.sharesIngredientWithOtherLines()).isTrue();
            assertThat(line.shortIngredients()).isNull();
        });
    }

    @Test
    void onlyInventoryReadersSeeShortIngredients() {
        var policy = new com.possaas.modules.inventory.service.InventoryAllocationPolicy();
        UUID tenant = UUID.randomUUID();
        assertThat(policy.revealsShortIngredients(new com.possaas.common.security.CurrentUser(UUID.randomUUID(), tenant, "w@x", "WAITER",
                Set.of("ORDER_CREATE", "ORDER_UPDATE")))).isFalse();
        assertThat(policy.revealsShortIngredients(new com.possaas.common.security.CurrentUser(UUID.randomUUID(), tenant, "m@x", "MANAGER",
                Set.of("ORDER_CREATE", "INVENTORY_VIEW")))).isTrue();
        assertThat(policy.revealsShortIngredients(null)).isFalse();
    }

    private List<ShortageLine> shortages(List<AcceptedItem> lines, Map<UUID, BigDecimal> available) {
        return calculator.shortages(lines, available, true);
    }

    private AcceptedItem line(String name, String quantity, String unit, Usage... usages) {
        return new AcceptedItem(UUID.randomUUID(), UUID.randomUUID(), "CAPTURED", d(quantity), unit, List.of(usages),
                false, UUID.randomUUID(), name);
    }

    private static Usage usage(UUID ingredient, String name, String quantity) { return usage(ingredient, name, quantity, "g"); }

    private static Usage usage(UUID ingredient, String name, String quantity, String unit) {
        return new Usage(UUID.randomUUID(), ingredient, null, name, unit, d(quantity));
    }

    private static BigDecimal d(String value) { return new BigDecimal(value).setScale(3); }
}
