package com.possaas.modules.cashbook;
import static org.assertj.core.api.Assertions.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.cashbook.dto.CashbookReadRequests;
import com.possaas.modules.cashbook.service.CashbookFilterPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;
class CashbookFilterPolicyTest {
    private final CashbookFilterPolicy policy=new CashbookFilterPolicy();
    @Test void rejectingSortInjectionAndUnsupportedFilterUsesBusinessErrors(){
        assertThatThrownBy(()->policy.page(new CashbookReadRequests.Page(0,20,"amount; DROP TABLE payments","desc"),"occurredAt",Set.of("amount","occurredAt")))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("INVALID_CASHBOOK_SORT"));
        assertThatThrownBy(()->policy.value("TRANSFERRED",Set.of("INCOME","EXPENSE"))).isInstanceOf(BusinessException.class);
    }
    @Test void pageBoundaryAndDirectionValidationAreEnforcedForDirectServiceCalls(){
        assertThatThrownBy(()->policy.page(new CashbookReadRequests.Page(-1,20,null,null),"amount",Set.of("amount"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->policy.page(new CashbookReadRequests.Page(0,101,null,null),"amount",Set.of("amount"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->policy.page(new CashbookReadRequests.Page(0,20,"amount","SIDEWAYS"),"amount",Set.of("amount"))).isInstanceOf(BusinessException.class);
    }
}
