package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.entity.InventoryBalance;
import com.possaas.modules.inventory.service.InventoryBalancePolicy;
import com.possaas.modules.inventory.service.InventoryValidationPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InventoryPolicyTest {
    private final InventoryBalancePolicy balancePolicy = new InventoryBalancePolicy();
    private final InventoryValidationPolicy validation = new InventoryValidationPolicy();

    @Test
    void normalizesWarehouseValuesAndPreservesExactThresholdScale() {
        assertThat(validation.code(" kho-a ")).isEqualTo("KHO-A");
        assertThat(validation.name(" Kho chính ")).isEqualTo("Kho chính");
        assertThat(validation.threshold(new BigDecimal("10.125"))).isEqualByComparingTo("10.125");
        assertThatThrownBy(() -> validation.threshold(new BigDecimal("1.0001")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
        assertThatThrownBy(() -> validation.threshold(new BigDecimal("-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void distinguishesLogicalAndExistingOptimisticVersions() {
        balancePolicy.requireLogicalVersion(null);
        assertThatThrownBy(() -> balancePolicy.requireLogicalVersion(0L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CONCURRENT_INVENTORY_UPDATE");

        InventoryBalance balance = new InventoryBalance();
        balancePolicy.requireExistingVersion(balance, 0L);
        assertThatThrownBy(() -> balancePolicy.requireExistingVersion(balance, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CONCURRENT_INVENTORY_UPDATE");
        assertThatThrownBy(() -> balancePolicy.requireExistingVersion(balance, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CONCURRENT_INVENTORY_UPDATE");
    }
}
