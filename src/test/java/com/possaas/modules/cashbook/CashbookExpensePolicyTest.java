package com.possaas.modules.cashbook;
import static org.assertj.core.api.Assertions.*;
import com.possaas.modules.cashbook.dto.CashbookExpenseRequests;
import com.possaas.modules.cashbook.service.CashbookExpensePolicy;
import com.possaas.modules.cashbook.service.CashbookExpenseIdempotency;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.modules.payment.entity.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class CashbookExpensePolicyTest {
    private final CashbookExpensePolicy policy=new CashbookExpensePolicy();
    private CashbookExpenseRequests.Create request(String amount,String currency) {
        return new CashbookExpenseRequests.Create(" rent ",new BigDecimal(amount),currency,PaymentMethod.CASH,
            Instant.parse("2026-10-29T01:00:00.123456789Z"),null," ref "," A "," note ");
    }
    @Test void rejectsZeroNegativeOverprecisionAndOverflowWithoutRounding() {
        for(String amount:new String[]{"0","-1","1.001","10000000000000000.00"})
            assertThatThrownBy(()->policy.normalize(request(amount,"VND"))).hasMessageContaining("Expense amount");
    }
    @Test void normalizesMoneyAndExplicitCurrencyAndKeepsDefaultMarkerForReplay() {
        var r=policy.normalize(request("10.0"," vnd "));
        assertThat(r.amount()).isEqualTo(new BigDecimal("10.00"));assertThat(r.categoryCode()).isEqualTo("RENT");
        assertThat(r.requestedCurrency()).isEqualTo("VND");assertThat(r.expenseAt().getNano()).isEqualTo(123456000);
        assertThat(policy.normalize(request("10",null)).requestedCurrency()).isNull();
        assertThatThrownBy(()->policy.currency("BAD")).hasMessageContaining("Currency");
    }
    @Test void preventsFutureExpensesButAllowsPastDates() {
        var now=Instant.parse("2026-10-29T02:00:00Z");
        policy.requirePaidTime(now.minusSeconds(86400),now);
        assertThatThrownBy(()->policy.requirePaidTime(now.plusSeconds(1),now)).hasMessageContaining("future");
    }
    @Test void fingerprintUsesNormalizedBusinessValuesAndActor() {
        var idem=new CashbookExpenseIdempotency(new ObjectMapper());var actor=UUID.randomUUID();
        assertThat(idem.fingerprint(actor,policy.normalize(request("10.00","VND"))))
            .isEqualTo(idem.fingerprint(actor,policy.normalize(request("10.0"," vnd "))));
        assertThat(idem.fingerprint(actor,policy.normalize(request("10.00",null))))
            .isNotEqualTo(idem.fingerprint(actor,policy.normalize(request("10.00","VND"))));
        assertThat(idem.fingerprint(actor,policy.normalize(request("10.00",null))))
            .isNotEqualTo(idem.fingerprint(UUID.randomUUID(),policy.normalize(request("10.00",null))));
    }
}
