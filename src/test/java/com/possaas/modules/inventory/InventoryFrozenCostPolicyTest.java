package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.application.port.RecipeUsageCostQuery.UsageCost;
import com.possaas.modules.inventory.entity.InventoryReservation;
import com.possaas.modules.inventory.entity.InventoryTransaction;
import com.possaas.modules.inventory.entity.OrderItemInventoryAllocation;
import com.possaas.modules.inventory.service.InventoryAllocationPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InventoryFrozenCostPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");
    private final InventoryAllocationPolicy policy = new InventoryAllocationPolicy();

    @Test
    void knownReferencePriceAndConfiguredZeroAreWrittenAndValidatedExactly() {
        for (String value : List.of("123.45", "0.00")) {
            Fixture fixture = fixture();
            UsageCost cost = cost(fixture, (short) 1, true, value);
            BigDecimal unitCost = policy.usageUnitCost(fixture.allocation(), fixture.reservation(), cost);
            InventoryTransaction ledger = consume(fixture, unitCost);

            assertThat(ledger.getUnitCost()).isEqualByComparingTo(value);
            assertThatCode(() -> history(fixture, ledger, cost)).doesNotThrowAnyException();
        }
    }

    @Test
    void unknownAndLegacyUsageKeepNullCostAndRemainConsumable() {
        for (UsageCostKind kind : UsageCostKind.values()) {
            Fixture fixture = fixture();
            UsageCost cost = kind == UsageCostKind.UNKNOWN
                    ? cost(fixture, (short) 1, false, null) : cost(fixture, null, null, null);
            BigDecimal unitCost = policy.usageUnitCost(fixture.allocation(), fixture.reservation(), cost);
            InventoryTransaction ledger = consume(fixture, unitCost);

            assertThat(unitCost).isNull();
            assertThat(ledger.getUnitCost()).isNull();
            assertThatCode(() -> history(fixture, ledger, cost)).doesNotThrowAnyException();
        }
    }

    @Test
    void legacyReservationCanStillBeValidatedAndReleased() {
        Fixture fixture = fixture();
        UsageCost legacy = cost(fixture, null, null, null);
        Map<UUID, UsageCost> facts = Map.of(legacy.recipeUsageId(), legacy);

        assertThatCode(() -> policy.history(fixture.allocation(), List.of(fixture.reservation()), Map.of(), facts))
                .doesNotThrowAnyException();
        fixture.allocation().release(NOW);
        assertThatCode(() -> policy.history(fixture.allocation(), List.of(fixture.reservation()), Map.of(), facts))
                .doesNotThrowAnyException();
    }

    @Test
    void mixedKnownAndUnknownUsagePreserveKnownLedgerPrice() {
        Fixture fixture = fixture();
        InventoryReservation other = new InventoryReservation(fixture.allocation(), UUID.randomUUID(),
                UUID.randomUUID(), "OTHER", "Other ingredient", "g", new BigDecimal("1.000"), NOW);
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        UsageCost known = cost(fixture, (short) 1, true, "20.00");
        UsageCost unknown = new UsageCost(known.orderId(), known.orderItemId(), known.recipeSnapshotId(),
                other.getRecipeUsageId(), other.getIngredientId(), other.getIngredientUnitSnapshot(),
                other.getQuantity(), (short) 1, false, null);
        InventoryTransaction knownLedger = consume(fixture, policy.usageUnitCost(fixture.allocation(),
                fixture.reservation(), known));
        InventoryTransaction unknownLedger = InventoryTransaction.recipeUse(fixture.allocation(), other,
                new BigDecimal("100.000"), new BigDecimal("99.000"),
                policy.usageUnitCost(fixture.allocation(), other, unknown), UUID.randomUUID(), NOW);

        assertThatCode(() -> policy.history(fixture.allocation(), List.of(fixture.reservation(), other),
                Map.of(fixture.reservation().getId(), knownLedger, other.getId(), unknownLedger),
                Map.of(known.recipeUsageId(), known, unknown.recipeUsageId(), unknown))).doesNotThrowAnyException();
        assertThat(knownLedger.getUnitCost()).isEqualByComparingTo("20.00");
        assertThat(unknownLedger.getUnitCost()).isNull();
    }

    @Test
    void existingLedgerMustMatchKnownUnknownAndLegacyDecision() {
        Fixture known = fixture();
        InventoryTransaction wrongKnown = consume(known, new BigDecimal("9.99"));
        assertConflict(() -> history(known, wrongKnown, cost(known, (short) 1, true, "10.00")));

        for (UsageCostKind kind : UsageCostKind.values()) {
            Fixture fixture = fixture();
            InventoryTransaction fakeZero = consume(fixture, BigDecimal.ZERO);
            UsageCost cost = kind == UsageCostKind.UNKNOWN
                    ? cost(fixture, (short) 1, false, null) : cost(fixture, null, null, null);
            assertConflict(() -> history(fixture, fakeZero, cost));
        }
    }

    @Test
    void missingHistoryAndMonetaryMetadataCorruptionAreConflicts() {
        Fixture fixture = fixture();
        assertConflict(() -> policy.history(fixture.allocation(), List.of(fixture.reservation()), Map.of(), Map.of()));
        for (UsageCost invalid : List.of(
                cost(fixture, null, true, "1.00"),
                cost(fixture, null, null, "1.00"),
                cost(fixture, (short) 2, true, "1.00"),
                cost(fixture, (short) 1, null, null),
                cost(fixture, (short) 1, true, null),
                cost(fixture, (short) 1, false, "0.00"),
                cost(fixture, (short) 1, true, "-0.01"),
                cost(fixture, (short) 1, true, "0.001"),
                cost(fixture, (short) 1, true, "1000000000000.00"))) {
            assertConflict(() -> policy.usageUnitCost(fixture.allocation(), fixture.reservation(), invalid));
        }
    }

    @Test
    void usageFactsMustMatchEveryReservationIdentityUnitAndQuantity() {
        Fixture fixture = fixture();
        UsageCost good = cost(fixture, (short) 1, true, "10.00");
        for (UsageCost invalid : List.of(
                new UsageCost(UUID.randomUUID(), good.orderItemId(), good.recipeSnapshotId(), good.recipeUsageId(),
                        good.ingredientId(), good.unit(), good.quantity(), (short) 1, true, good.unitCost()),
                new UsageCost(good.orderId(), UUID.randomUUID(), good.recipeSnapshotId(), good.recipeUsageId(),
                        good.ingredientId(), good.unit(), good.quantity(), (short) 1, true, good.unitCost()),
                new UsageCost(good.orderId(), good.orderItemId(), UUID.randomUUID(), good.recipeUsageId(),
                        good.ingredientId(), good.unit(), good.quantity(), (short) 1, true, good.unitCost()),
                new UsageCost(good.orderId(), good.orderItemId(), good.recipeSnapshotId(), UUID.randomUUID(),
                        good.ingredientId(), good.unit(), good.quantity(), (short) 1, true, good.unitCost()),
                new UsageCost(good.orderId(), good.orderItemId(), good.recipeSnapshotId(), good.recipeUsageId(),
                        UUID.randomUUID(), good.unit(), good.quantity(), (short) 1, true, good.unitCost()),
                new UsageCost(good.orderId(), good.orderItemId(), good.recipeSnapshotId(), good.recipeUsageId(),
                        good.ingredientId(), "kg", good.quantity(), (short) 1, true, good.unitCost()),
                new UsageCost(good.orderId(), good.orderItemId(), good.recipeSnapshotId(), good.recipeUsageId(),
                        good.ingredientId(), good.unit(), new BigDecimal("2.001"), (short) 1, true, good.unitCost()))) {
            assertConflict(() -> policy.usageUnitCost(fixture.allocation(), fixture.reservation(), invalid));
        }
    }

    @Test
    void ledgerStillRequiresCorrectReservationAndQuantityHistory() {
        Fixture fixture = fixture();
        UsageCost cost = cost(fixture, (short) 1, true, "10.00");
        InventoryTransaction ledger = consume(fixture, cost.unitCost());
        ReflectionTestUtils.setField(ledger, "reservationId", UUID.randomUUID());
        assertConflict(() -> history(fixture, ledger, cost));
        ReflectionTestUtils.setField(ledger, "reservationId", fixture.reservation().getId());
        ReflectionTestUtils.setField(ledger, "afterQuantity", new BigDecimal("99.000"));
        assertConflict(() -> history(fixture, ledger, cost));
    }

    private void history(Fixture fixture, InventoryTransaction ledger, UsageCost cost) {
        policy.history(fixture.allocation(), List.of(fixture.reservation()),
                Map.of(fixture.reservation().getId(), ledger), Map.of(cost.recipeUsageId(), cost));
    }

    private static InventoryTransaction consume(Fixture fixture, BigDecimal unitCost) {
        fixture.allocation().consume(NOW);
        return InventoryTransaction.recipeUse(fixture.allocation(), fixture.reservation(), new BigDecimal("100.000"),
                new BigDecimal("98.000"), unitCost, UUID.randomUUID(), NOW);
    }

    private static UsageCost cost(Fixture fixture, Short version, Boolean configured, String amount) {
        return new UsageCost(fixture.allocation().getOrderId(), fixture.allocation().getOrderItemId(),
                fixture.allocation().getRecipeSnapshotId(), fixture.reservation().getRecipeUsageId(),
                fixture.reservation().getIngredientId(), fixture.reservation().getIngredientUnitSnapshot(),
                fixture.reservation().getQuantity(), version, configured, amount == null ? null : new BigDecimal(amount));
    }

    private static Fixture fixture() {
        OrderItemInventoryAllocation allocation = new OrderItemInventoryAllocation(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1.000"), "portion", UUID.randomUUID(), null, NOW);
        ReflectionTestUtils.setField(allocation, "id", UUID.randomUUID());
        InventoryReservation reservation = new InventoryReservation(allocation, UUID.randomUUID(), UUID.randomUUID(),
                "ING", "Ingredient", "g", new BigDecimal("2.000"), NOW);
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        return new Fixture(allocation, reservation);
    }

    private static void assertConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVENTORY_ALLOCATION_CONFLICT");
    }

    private record Fixture(OrderItemInventoryAllocation allocation, InventoryReservation reservation) {}
    private enum UsageCostKind { UNKNOWN, LEGACY }
}
