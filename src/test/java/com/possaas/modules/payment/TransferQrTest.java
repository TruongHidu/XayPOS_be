package com.possaas.modules.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.payment.vietqr.VietQrPayloadBuilder;
import com.possaas.infrastructure.qrcode.QrCodeRenderer;
import com.possaas.infrastructure.qrcode.QrImageFormat;
import com.possaas.modules.order.application.port.PayableOrderQuery;
import com.possaas.modules.order.application.port.PayableOrderQuery.PayableOrder;
import com.possaas.modules.payment.application.port.ReceivingAccountQuery;
import com.possaas.modules.payment.application.port.ReceivingAccountQuery.ReceivingAccount;
import com.possaas.modules.payment.service.OrderTransferQrQueryService;
import com.possaas.modules.payment.service.PaymentAccessService;
import com.possaas.modules.payment.service.TransferQrPolicy;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferQrTest {
    private final TransferQrPolicy policy = new TransferQrPolicy();
    private final VietQrPayloadBuilder vietQr = new VietQrPayloadBuilder();
    private final UUID tenant = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final PaymentAccessService access = mock(PaymentAccessService.class);
    private final PayableOrderQuery orders = mock(PayableOrderQuery.class);
    private final ReceivingAccountQuery accounts = mock(ReceivingAccountQuery.class);
    private final OrderTransferQrQueryService service =
        new OrderTransferQrQueryService(access, orders, accounts, policy, vietQr, new QrCodeRenderer());

    @BeforeEach void setUp() {
        when(access.read()).thenReturn(new CurrentUser(UUID.randomUUID(), tenant, "c@x.vn", "CASHIER", Set.of()));
    }

    @Test void contentIsShortAndFitsVietQr() {
        String content = policy.transferContent("OD-9F3C2B1A0E5D4C3B2A1F0E9D8C7B6A5F");
        assertThat(content).isEqualTo("TT 9D8C7B6A5F").matches("[0-9A-Za-z ]{1,25}");
        assertThat(policy.transferContent("od-12")).isEqualTo("TT OD12");
    }

    @Test void onlyWholeDongIsAccepted() {
        assertThat(policy.amountInDong("VND", new BigDecimal("350000.00"))).isEqualTo(350000L);
        assertThatThrownBy(() -> policy.amountInDong("USD", new BigDecimal("10.00")))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("TRANSFER_QR_CURRENCY_UNSUPPORTED");
        assertThatThrownBy(() -> policy.amountInDong("VND", new BigDecimal("10.50")))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("TRANSFER_QR_CURRENCY_UNSUPPORTED");
    }

    @Test void buildsTheCodeFromTheCurrentBillAndTheRestaurantAccount() {
        when(orders.requirePayable(tenant, orderId)).thenReturn(payable("350000.00", "VND"));
        when(accounts.find(tenant)).thenReturn(Optional.of(new ReceivingAccount("970436", "0123456789", "QUAN AN")));

        var qr = service.get(orderId);

        assertThat(qr.orderVersion()).isEqualTo(7);
        assertThat(qr.amount()).isEqualByComparingTo("350000");
        assertThat(qr.transferContent()).isEqualTo("TT 1A2B3C4D5E");
        assertThat(qr.qrPayload())
            .isEqualTo(vietQr.accountTransfer("970436", "0123456789", 350000L, "TT 1A2B3C4D5E"))
            .contains("970436").contains("0123456789").contains("350000").contains("TT 1A2B3C4D5E");
        assertThat(service.image(orderId, QrImageFormat.PNG, 256).content()).isNotEmpty();
    }

    @Test void noAccountMeansNoCode() {
        when(orders.requirePayable(tenant, orderId)).thenReturn(payable("350000.00", "VND"));
        when(accounts.find(tenant)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(orderId))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("TRANSFER_ACCOUNT_NOT_CONFIGURED");
    }

    @Test void anOrderThatCannotBePaidNeverReachesTheAccount() {
        when(orders.requirePayable(any(), any()))
            .thenThrow(new ConflictException("ORDER_ALREADY_PAID", "Order is already paid"));
        assertThatThrownBy(() -> service.get(orderId))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ORDER_ALREADY_PAID");
        verifyNoInteractions(accounts);
    }

    private PayableOrder payable(String total, String currency) {
        return new PayableOrder(orderId, "OD-00000000000000000000001A2B3C4D5E", new BigDecimal(total), currency, 7);
    }
}
