package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.service.WeightedAverageCostPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeightedAverageCostPolicyTest {
    private final WeightedAverageCostPolicy policy = new WeightedAverageCostPolicy();

    @ParameterizedTest
    @CsvSource({
        "10,100,5,130,110.00",
        "0,999,5,130,130.00",
        "0,0,1,0,0.00",
        "10,100,10,0,50.00",
        "10,0,10,100,50.00",
        "0.125,10,0.375,20,17.50",
        "1,0,1,0.01,0.01",
        "1,1,2,2,1.67",
        "99999999999.999,999999999999.99,0.001,999999999999.99,999999999999.99"
    })
    void calculatesWithExactDecimalArithmeticAndFinalHalfUpRounding(String oldQuantity, String oldCost,
            String quantity, String cost, String expected) {
        assertThat(policy.calculate(decimal(oldQuantity), decimal(oldCost), decimal(quantity), decimal(cost)))
                .isEqualTo(decimal(expected));
    }

    @ParameterizedTest
    @CsvSource({
        ",1,1,1", "1,,1,1", "1,1,,1", "1,1,1,",
        "-1,1,1,1", "1,-1,1,1", "1,1,0,1", "1,1,-1,1", "1,1,1,-1"
    })
    void refusesInvalidCostInputs(String oldQuantity, String oldCost, String quantity, String cost) {
        assertThatThrownBy(() -> policy.calculate(decimal(oldQuantity), decimal(oldCost),
                decimal(quantity), decimal(cost)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("INVALID_INVENTORY_COST_DATA"));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
