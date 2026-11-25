package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.menu.application.port.OrderMenuQuery;
import com.possaas.modules.menu.application.port.Sellability;
import com.possaas.modules.menu.application.port.SellableMenuItem;
import com.possaas.modules.order.service.OrderMoneyCalculator;
import com.possaas.modules.qrorder.dto.QrStaffRequests.ExcludedItem;
import com.possaas.modules.qrorder.entity.QrOrderRequestItem;
import com.possaas.modules.qrorder.service.QrApprovalLinePolicy;
import com.possaas.modules.qrorder.service.QrQuoteService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class QrPartialApprovalTest {
    private final UUID tenant = UUID.randomUUID(), pho = UUID.randomUUID(), tea = UUID.randomUUID();
    private final OrderMenuQuery menu = mock(OrderMenuQuery.class);
    private final QrQuoteService quotes = new QrQuoteService(menu, new OrderMoneyCalculator());
    private final QrApprovalLinePolicy policy = new QrApprovalLinePolicy(quotes);
    private final QrOrderRequestItem phoLine = line(1, pho, "Phở", "50000.00"), teaLine = line(2, tea, "Trà đá", "5000.00");

    @Test void reviewJudgesEachLineAndPricesOnlyTheAvailableOnes() {
        stock(true, false);
        var quote = quotes.review(tenant, "VND", List.of(phoLine, teaLine));
        assertThat(quote.amount()).isEqualByComparingTo("50000");
        var available = quote.lines().get(0); var out = quote.lines().get(1);
        assertThat(available.available()).isTrue(); assertThat(available.requestItemId()).isEqualTo(phoLine.getId());
        assertThat(out.available()).isFalse(); assertThat(out.unavailableReason()).isEqualTo("ITEM_OUT_OF_STOCK");
        assertThat(out.unitPrice()).isNull(); assertThat(out.lineTotal()).isNull();
        assertThat(out.itemName()).isEqualTo("Trà đá");
        assertThat(quote.allUnavailable()).isFalse();
        verify(menu, never()).requireSellable(any(), any());
    }

    @Test void unavailableLineMustBeLeftOutAndTheRestIsApprovedAtCurrentPrice() {
        stock(true, false);
        var quote = quotes.review(tenant, "VND", List.of(phoLine, teaLine));
        assertCode(() -> policy.decide(List.of(phoLine, teaLine), quote, List.of()), "QR_LINE_UNAVAILABLE");
        var decision = policy.decide(List.of(phoLine, teaLine), quote, List.of(new ExcludedItem(teaLine.getId(), "  Hết trà  ")));
        assertThat(decision.included()).containsExactly(phoLine);
        assertThat(decision.excluded()).singleElement().satisfies(e -> {
            assertThat(e.item()).isSameAs(teaLine); assertThat(e.reason()).isEqualTo("Hết trà");
        });
        assertThat(decision.approvedAmount()).isEqualByComparingTo("50000");
        assertThat(decision.excludedIds()).containsExactly(teaLine.getId());
    }

    @Test void staffMayAlsoLeaveOutAnAvailableLine() {
        stock(true, true);
        var quote = quotes.review(tenant, "VND", List.of(phoLine, teaLine));
        var decision = policy.decide(List.of(phoLine, teaLine), quote, List.of(new ExcludedItem(phoLine.getId(), "Bếp quá tải")));
        assertThat(decision.included()).containsExactly(teaLine);
        assertThat(decision.approvedAmount()).isEqualByComparingTo("5000");
    }

    @Test void leavingEverythingOutOrNamingForeignOrDuplicateLinesIsRejected() {
        stock(true, true);
        var quote = quotes.review(tenant, "VND", List.of(phoLine, teaLine));
        var items = List.of(phoLine, teaLine);
        assertCode(() -> policy.decide(items, quote, List.of(new ExcludedItem(phoLine.getId(), "a"), new ExcludedItem(teaLine.getId(), "b"))),
            "QR_APPROVAL_EMPTY");
        assertCode(() -> policy.decide(items, quote, List.of(new ExcludedItem(UUID.randomUUID(), "a"))), "QR_EXCLUDED_ITEM_INVALID");
        assertCode(() -> policy.decide(items, quote, List.of(new ExcludedItem(phoLine.getId(), "a"), new ExcludedItem(phoLine.getId(), "b"))),
            "QR_EXCLUDED_ITEM_INVALID");
    }

    @Test void nothingAvailableIsReportedAsAllUnavailable() {
        stock(false, false);
        assertThat(quotes.review(tenant, "VND", List.of(phoLine, teaLine)).allUnavailable()).isTrue();
        assertThat(new QrQuoteService.QrQuote("VND", BigDecimal.ZERO, List.of()).allUnavailable()).isFalse();
    }

    @Test void leftOutLineKeepsItsReasonAndIsNeverLinkedToAnOrderLine() {
        var now = Instant.parse("2026-11-25T03:00:00Z");
        teaLine.reject("Hết trà", now);
        assertThat(teaLine.getRejectionReason()).isEqualTo("Hết trà");
        assertThat(teaLine.getRejectedAt()).isEqualTo(now); assertThat(teaLine.getApprovedOrderItemId()).isNull();
    }

    private void stock(boolean phoSellable, boolean teaSellable) {
        var result = new HashMap<UUID, Sellability>();
        result.put(pho, phoSellable ? Sellability.sellable(new SellableMenuItem(pho, "Phở", "tô", new BigDecimal("50000")))
            : Sellability.unavailable("ITEM_OUT_OF_STOCK"));
        result.put(tea, teaSellable ? Sellability.sellable(new SellableMenuItem(tea, "Trà đá", "ly", new BigDecimal("5000")))
            : Sellability.unavailable("ITEM_OUT_OF_STOCK"));
        when(menu.inspectSellable(tenant, List.of(pho, tea))).thenReturn(result);
    }
    private static QrOrderRequestItem line(int number, UUID itemId, String name, String price) {
        var line = new QrOrderRequestItem(); line.setId(UUID.randomUUID()); line.setLineNumber(number); line.setItemId(itemId);
        line.setQuantity(new BigDecimal("1.000")); line.setQuotedItemName(name); line.setQuotedUnit("phần");
        line.setQuotedUnitPrice(new BigDecimal(price)); line.setQuotedLineTotal(new BigDecimal(price)); return line;
    }
    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
            .satisfies(f -> assertThat(((BusinessException) f).getCode()).isEqualTo(code));
    }
}
