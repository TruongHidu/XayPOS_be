package com.possaas.modules.qrorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.application.port.InsufficientStockException;
import com.possaas.modules.order.application.port.InsufficientStockException.ShortIngredient;
import com.possaas.modules.order.application.port.InsufficientStockException.ShortageLine;
import com.possaas.modules.qrorder.service.QrRateLimiter;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QrStockShortageErrorTest {
    private final InsufficientStockException shortage = new InsufficientStockException(List.of(new ShortageLine(
            UUID.randomUUID(), UUID.randomUUID(), "Phở bò", new BigDecimal("3.000"), new BigDecimal("1.000"), "tô", true,
            List.of(new ShortIngredient(UUID.randomUUID(), "Thịt bò", "g", new BigDecimal("500.000"), new BigDecimal("630.000"))))));

    @Test
    void staffApprovalReceivesShortageDetails() {
        var response = new QrStaffErrorHandler(Clock.systemUTC()).business(shortage);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(response.getBody().details()).isSameAs(shortage.details());
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void staffErrorsWithoutDetailsStayUnchanged() {
        var response = new QrStaffErrorHandler(Clock.systemUTC()).business(new ConflictException("ITEM_OUT_OF_STOCK", "Item is out of stock"));
        assertThat(response.getBody().details()).isNull();
    }

    @Test
    void guestNeverReceivesInventoryDetails() {
        var response = new PublicQrOrderErrorHandler(Clock.systemUTC(), mock(QrRateLimiter.class)).business(shortage);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().code()).isEqualTo("QR_ORDER_UNAVAILABLE");
        assertThat(response.getBody().details()).isNull();
    }
}
