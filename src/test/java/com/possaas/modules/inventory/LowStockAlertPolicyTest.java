package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.service.LowStockAlertPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LowStockAlertPolicyTest {
    final LowStockAlertPolicy policy=new LowStockAlertPolicy();
    final Instant now=Instant.parse("2026-10-21T08:00:00Z");

    @ParameterizedTest @CsvSource({"0,0,false","100,0,false","201,200,false","200,200,true","0,200,true","199.999,200,true"})
    void physicalThreshold(String quantity,String threshold,boolean expected) {
        assertThat(policy.low(new BigDecimal(quantity),new BigDecimal(threshold))).isEqualTo(expected);
    }
    @Test void availableQuantityIsNotTheTriggerMetric() {
        var balance=new InventoryBalance();balance.setQuantity(new BigDecimal("1000"));
        balance.setReservedQuantity(new BigDecimal("900"));balance.setMinQuantity(new BigDecimal("200"));
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("100");
        assertThat(policy.low(balance.getQuantity(),balance.getMinQuantity())).isFalse();
    }
    @ParameterizedTest @CsvSource({
        "true,true,false,true,true,10,20,WAREHOUSE_DELETED",
        "false,false,false,true,true,10,20,WAREHOUSE_INACTIVE",
        "false,true,true,true,true,10,20,INGREDIENT_DELETED",
        "false,true,false,false,true,10,20,INGREDIENT_UNTRACKED",
        "false,true,false,true,false,10,20,INGREDIENT_UNIT_CHANGED",
        "false,true,false,true,true,10,0,THRESHOLD_DISABLED",
        "false,true,false,true,true,21,20,STOCK_RECOVERED"})
    void resolvesForExplicitReason(boolean wd,boolean wa,boolean deleted,boolean tracked,boolean sameUnit,
            String quantity,String min,LowStockAlertResolutionReason expected) {
        assertThat(policy.resolution(wd,wa,deleted,tracked,sameUnit,new BigDecimal(quantity),new BigDecimal(min))).isEqualTo(expected);
    }
    @Test void remainingLowKeepsEpisodeAndAckReplayDoesNotChangeSnapshots() {
        var alert=alert();assertThat(policy.requireAcknowledge(alert,0)).isTrue();
        UUID actor=UUID.randomUUID();alert.acknowledge(actor,now.plusSeconds(1));
        assertThat(policy.requireAcknowledge(alert,99)).isFalse();
        assertThat(policy.resolution(false,true,false,true,true,BigDecimal.ONE,BigDecimal.TEN)).isNull();
        alert.resolve(LowStockAlertResolutionReason.STOCK_RECOVERED,now.plusSeconds(2));
        assertThat(alert.getAcknowledgedBy()).isEqualTo(actor);
        assertThat(alert.getQuantityAtTrigger()).isEqualByComparingTo("5");
        assertThat(alert.getThresholdQuantity()).isEqualByComparingTo("10");
        assertThatThrownBy(()->policy.requireAcknowledge(alert,0)).isInstanceOf(BusinessException.class)
            .extracting("code").isEqualTo("INVALID_LOW_STOCK_ALERT_TRANSITION");
    }
    @Test void rejectsStaleVersionOnOpen() {
        assertThatThrownBy(()->policy.requireAcknowledge(alert(),1)).isInstanceOf(BusinessException.class)
            .extracting("code").isEqualTo("CONCURRENT_LOW_STOCK_ALERT_UPDATE");
    }
    LowStockAlert alert() {return new LowStockAlert(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
        "MAIN","Main","BEEF","Beef","g",new BigDecimal("5"),BigDecimal.TEN,now);}
}
