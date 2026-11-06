package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog.TrackedIngredientSnapshot;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StockAuditPolicyTest {
    final StockAuditPolicy policy=new StockAuditPolicy();
    final Instant now=Instant.parse("2026-10-21T08:00:00Z");
    StockAudit audit(){return new StockAudit(UUID.randomUUID(),UUID.randomUUID(),"AUD","KEY","hash",null,UUID.randomUUID(),now);}
    @Test void lifecycleAndAggregateVersionDoNotDependOnWallClockAdvancing(){
        var audit=audit();policy.requireStatus(audit,StockAuditStatus.DRAFT);policy.requireEditable(audit);policy.requireVersion(audit,0);
        assertThatThrownBy(()->policy.requireVersion(audit,1)).hasMessageContaining("reload");
        audit.start(UUID.randomUUID(),now);policy.requireStatus(audit,StockAuditStatus.COUNTING);audit.counted(now);audit.counted(now);assertThat(audit.getCountRevision()).isEqualTo(2);
        audit.confirm(UUID.randomUUID(),now);assertThatThrownBy(()->policy.requireEditable(audit)).hasMessageContaining("not allowed");
    }
    @ParameterizedTest @CsvSource({"10,8,-2","10,12,2","10,10,0","0,0,0"})
    void nullCountIsNotZeroAndDifferencesHaveCorrectSign(String system,String counted,String delta){
        var item=new StockAuditItem(audit(),new TrackedIngredientSnapshot(UUID.randomUUID(),null,"Food","g",true,true,false),new BigDecimal(system),BigDecimal.ZERO,0,now);
        assertThat(item.getCountedQuantity()).isNull();assertThat(item.difference()).isNull();
        assertThat(item.count(new BigDecimal(counted)," note ",now)).isTrue();assertThat(item.difference()).isEqualByComparingTo(delta);assertThat(item.getNote()).isEqualTo("note");
        assertThat(item.count(new BigDecimal(counted),null,now)).isFalse();assertThat(item.count(new BigDecimal(counted)," ",now)).isTrue();assertThat(item.getNote()).isNull();
    }
    @Test void monetaryPrecisionUsesHalfUpAndRejectsOverflows(){
        assertThat(policy.price(BigDecimal.ZERO)).isEqualByComparingTo("0.00");
        assertThat(policy.lineCost(new BigDecimal("0.125"),new BigDecimal("0.12"))).isEqualByComparingTo("0.02");
        for(BigDecimal cost:new BigDecimal[]{null,new BigDecimal("-1"),new BigDecimal("0.001"),new BigDecimal("1000000000000")})assertThatThrownBy(()->policy.price(cost)).hasMessageContaining("reference cost");
        assertThatThrownBy(()->policy.lineCost(new BigDecimal("99999999999.999"),new BigDecimal("999999999999.99"))).hasMessageContaining("precision");
        assertThatThrownBy(()->policy.documentTotals(new BigDecimal("10000000000000"),BigDecimal.ZERO)).hasMessageContaining("precision");
        assertThatThrownBy(()->policy.documentTotals(BigDecimal.ZERO,new BigDecimal("10000000000000000"))).hasMessageContaining("precision");
    }
    @Test void reservationInvariantAndPostingNeverPermitNegativeAvailable(){
        var quantities=new InventoryAvailabilityPolicy();var balance=new InventoryBalance();balance.initialize(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.ZERO,now);balance.applyQuantityChange(new BigDecimal("10"),now);
        assertThatThrownBy(()->quantities.invariant(balance,BigDecimal.ONE)).hasMessageContaining("inconsistent");
        quantities.requireStockPosting(new BigDecimal("8"),new BigDecimal("6"));assertThatThrownBy(()->quantities.requireStockPosting(new BigDecimal("8"),new BigDecimal("9"))).hasMessageContaining("reserved");
    }
}
