package com.possaas.modules.payment.controller;

import com.possaas.infrastructure.qrcode.QrCodeRenderer;
import com.possaas.infrastructure.qrcode.QrImageFormat;
import com.possaas.modules.payment.dto.TransferQrResponse;
import com.possaas.modules.payment.service.OrderTransferQrQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Transfer QR shown to the customer at checkout. Showing it never records a payment. */
@Validated @RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{orderId}/transfer-qr")
@PreAuthorize("hasAuthority('PAYMENT_CREATE')")
public class OrderTransferQrController {
    private final OrderTransferQrQueryService query;

    @GetMapping
    public ResponseEntity<TransferQrResponse> get(@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.get(orderId));
    }

    @GetMapping("/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID orderId,
            @RequestParam(defaultValue = "png") String format,
            @RequestParam(defaultValue = "" + QrCodeRenderer.DEFAULT_SIZE)
            @Min(QrCodeRenderer.MIN_SIZE) @Max(QrCodeRenderer.MAX_SIZE) int size) {
        return query.image(orderId, QrImageFormat.parse(format), size).toResponse(false);
    }
}
