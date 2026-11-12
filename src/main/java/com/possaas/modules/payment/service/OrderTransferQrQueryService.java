package com.possaas.modules.payment.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.infrastructure.payment.vietqr.VietQrPayloadBuilder;
import com.possaas.infrastructure.qrcode.QrCodeRenderer;
import com.possaas.infrastructure.qrcode.QrImage;
import com.possaas.infrastructure.qrcode.QrImageFormat;
import com.possaas.modules.order.application.port.PayableOrderQuery;
import com.possaas.modules.payment.application.port.ReceivingAccountQuery;
import com.possaas.modules.payment.dto.TransferQrResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the customer-facing transfer QR for an order. Read-only by design: no payment row, no audit, no
 * state change; the cashier records the payment separately after checking the money arrived.
 */
@Service
@RequiredArgsConstructor
public class OrderTransferQrQueryService {
    private final PaymentAccessService access;
    private final PayableOrderQuery orders;
    private final ReceivingAccountQuery accounts;
    private final TransferQrPolicy policy;
    private final VietQrPayloadBuilder vietQr;
    private final QrCodeRenderer codes;

    @Transactional(readOnly = true)
    public TransferQrResponse get(UUID orderId) {
        UUID tenant = access.read().restaurantId();
        var order = orders.requirePayable(tenant, orderId);
        long amount = policy.amountInDong(order.currencyCode(), order.totalAmount());
        var account = accounts.find(tenant).orElseThrow(() -> new ConflictException(
            "TRANSFER_ACCOUNT_NOT_CONFIGURED", "The restaurant has no transfer account yet"));
        String content = policy.transferContent(order.orderCode());
        String payload = vietQr.accountTransfer(account.bankBin(), account.accountNumber(), amount, content);
        return new TransferQrResponse(order.orderId(), order.version(), order.totalAmount(), order.currencyCode(),
            account.bankBin(), account.accountNumber(), account.accountName(), content, payload);
    }

    @Transactional(readOnly = true)
    public QrImage image(UUID orderId, QrImageFormat format, int size) {
        String payload = get(orderId).qrPayload();
        return format == QrImageFormat.SVG ? QrImage.svg(codes.svg(payload, size), "chuyen-khoan")
            : QrImage.png(codes.png(payload, size), "chuyen-khoan");
    }
}
