package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.service.RecipeUsageQuantityCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecipeUsageQuantityCalculatorTest {
    private final RecipeUsageQuantityCalculator calculator = new RecipeUsageQuantityCalculator();
    @ParameterizedTest
    @CsvSource({"1000,5,3,10,315.000", "0.001,0,1,10,0.001", "1,0,1,3,0.334",
        "1000,0,1,3,333.334", "1000,100,3,10,600.000", "0.125,5,2.5,10,0.033",
        "99999999999.999,0,1,1,99999999999.999"})
    void roundsOnlyTheFinalResultUp(String quantity, String waste, String ordered, String yield, String expected) {
        assertThat(calculator.calculate(d(quantity),d(waste),d(ordered),d(yield))).isEqualTo(d(expected));
    }
    @ParameterizedTest
    @CsvSource({",0,1,1", "1,,1,1", "1,0,,1", "1,0,1,", "0,0,1,1", "-1,0,1,1",
        "1,-1,1,1", "1,101,1,1", "1,0,0,1", "1,0,1,0"})
    void invalidInputsHaveStableBusinessError(String quantity, String waste, String ordered, String yield) {
        assertThatThrownBy(() -> calculator.calculate(d(quantity),d(waste),d(ordered),d(yield)))
            .isInstanceOfSatisfying(ConflictException.class,e -> assertThat(e.getCode()).isEqualTo("INVALID_RECIPE_SNAPSHOT_DATA"));
    }
    @ParameterizedTest @CsvSource({"99999999999.999,1,1,1", "1000,0,999999999.999,0.001"})
    void overflowIsRejectedBeforePersistence(String quantity, String waste, String ordered, String yield) {
        assertThatThrownBy(() -> calculator.calculate(d(quantity),d(waste),d(ordered),d(yield)))
            .isInstanceOfSatisfying(ConflictException.class,e -> assertThat(e.getCode()).isEqualTo("RECIPE_USAGE_QUANTITY_OUT_OF_RANGE"));
    }
    private static BigDecimal d(String value) { return value == null ? null : new BigDecimal(value); }
}
