package com.possaas.modules.cashbook.dto;
import com.possaas.modules.payment.entity.PaymentMethod;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public final class CashbookExpenseRequests {
    private CashbookExpenseRequests() {}
    public record Create(@NotBlank @Size(max=50) String categoryCode,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=2) BigDecimal amount,
        @Size(max=3) String currencyCode,@NotNull PaymentMethod paymentMethod,@NotNull Instant expenseAt,
        UUID stockDocumentId,@Size(max=100) String referenceNo,@Size(max=150) String payeeName,
        @Size(max=10000) String note) {}
    public record Void(@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=1000) String reason) {}
}
