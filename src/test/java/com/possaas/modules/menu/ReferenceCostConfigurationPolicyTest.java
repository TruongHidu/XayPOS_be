package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.menu.service.*;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReferenceCostConfigurationPolicyTest {
    private final ReferenceCostConfigurationPolicy policy = new ReferenceCostConfigurationPolicy(new MenuItemValidationPolicy());

    static Stream<Arguments> creations() {
        return Stream.of(Arguments.of(null,null,"0",false), Arguments.of("0",null,"0",true),
                Arguments.of("12.50",null,"12.50",true), Arguments.of("0",true,"0",true),
                Arguments.of(null,false,"0",false), Arguments.of("0",false,"0",false));
    }
    @ParameterizedTest @MethodSource("creations")
    void createDistinguishesOmittedFromExplicitZero(String price, Boolean configured, String expected, boolean known) {
        var result=policy.create(price==null?null:new BigDecimal(price),configured);
        assertThat(result.price()).isEqualByComparingTo(expected);
        assertThat(result.configured()).isEqualTo(known);
        assertThat(result.price().scale()).isEqualTo(2);
    }
    static Stream<Arguments> patches() {
        return Stream.of(Arguments.of("12.50",true,null,null,"12.50",true),
                Arguments.of("0",false,null,null,"0",false), Arguments.of("0",false,"0",null,"0",true),
                Arguments.of("0",false,null,true,"0",true), Arguments.of("12.50",true,null,false,"0",false),
                Arguments.of("12.50",true,"0",false,"0",false), Arguments.of("0",false,"7.25",null,"7.25",true));
    }
    @ParameterizedTest @MethodSource("patches")
    void patchKeepsConfirmsOrClearsTheMeaningOfPrice(String old, boolean known, String price, Boolean configured,
            String expected, boolean expectedKnown) {
        var result=policy.update(new BigDecimal(old),known,price==null?null:new BigDecimal(price),configured);
        assertThat(result.price()).isEqualByComparingTo(expected);
        assertThat(result.configured()).isEqualTo(expectedKnown);
    }
    @Test void configuredCreateNeedsAnExplicitValueAndUnconfiguredCannotHideAPositivePrice() {
        assertThatThrownBy(()->policy.create(null,true)).isInstanceOfSatisfying(BusinessException.class,
                e->assertThat(e.getCode()).isEqualTo("COST_PRICE_REQUIRED"));
        assertThatThrownBy(()->policy.create(BigDecimal.ONE,false)).isInstanceOfSatisfying(BusinessException.class,
                e->assertThat(e.getCode()).isEqualTo("INVALID_COST_CONFIGURATION"));
        assertThatThrownBy(()->policy.update(BigDecimal.TEN,true,BigDecimal.ONE,false)).isInstanceOf(BusinessException.class);
    }
}
