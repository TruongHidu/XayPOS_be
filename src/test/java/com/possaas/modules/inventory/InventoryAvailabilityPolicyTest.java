package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.entity.InventoryBalance;
import com.possaas.modules.inventory.service.InventoryAvailabilityPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InventoryAvailabilityPolicyTest {
    private final InventoryAvailabilityPolicy policy = new InventoryAvailabilityPolicy();

    @Test
    void rejectsOverflowAndUnrepresentableQuantitiesBeforePersistence() {
        assertThat(policy.quantity(new BigDecimal("99999999999.999")).scale()).isEqualTo(3);
        assertCode(() -> policy.quantity(new BigDecimal("100000000000.000")), "INVENTORY_QUANTITY_OUT_OF_RANGE");
        assertCode(() -> policy.quantity(new BigDecimal("0.0001")), "INVENTORY_QUANTITY_OUT_OF_RANGE");
        assertCode(() -> policy.quantity(new BigDecimal("-0.001")), "INVENTORY_QUANTITY_OUT_OF_RANGE");
    }

    @Test
    void reconcilesCacheAndUsesAvailableForNewDemand() {
        InventoryBalance balance = new InventoryBalance();
        balance.setQuantity(new BigDecimal("1000.000")); balance.setReservedQuantity(new BigDecimal("315.000"));
        policy.invariant(balance, new BigDecimal("315.000"));
        policy.requireAvailable(balance, new BigDecimal("685.000"));
        assertCode(() -> policy.requireAvailable(balance, new BigDecimal("685.001")), "INSUFFICIENT_AVAILABLE_STOCK");
        assertCode(() -> policy.invariant(balance, new BigDecimal("314.000")), "INVENTORY_RESERVATION_INVARIANT_VIOLATION");
        assertCode(() -> policy.requireAvailable(null, new BigDecimal("0.001")), "INSUFFICIENT_AVAILABLE_STOCK");
    }

    @Test
    void stockPostingProtectsReservedQuantityAndPreservesLegacyNoStockError() {
        policy.requireStockPosting(new BigDecimal("315.000"), new BigDecimal("315.000"));
        assertCode(() -> policy.requireStockPosting(new BigDecimal("314.999"), new BigDecimal("315.000")),
                "INSUFFICIENT_AVAILABLE_STOCK");
        assertCode(() -> policy.requireStockPosting(new BigDecimal("-1.000"), BigDecimal.ZERO), "INSUFFICIENT_STOCK");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(code);
    }
}
