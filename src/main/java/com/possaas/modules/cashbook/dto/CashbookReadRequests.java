package com.possaas.modules.cashbook.dto;
import com.possaas.modules.payment.entity.PaymentMethod;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
public final class CashbookReadRequests {
    private CashbookReadRequests() {}
    public record Filter(
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDate,
        String type, String sourceType, @Size(max=80) String categoryCode,
        PaymentMethod paymentMethod, String currencyCode, UUID createdBy, @Size(max=100) String q) {}
    public record Page(@Min(0) Integer page, @Min(1) @Max(100) Integer size, String sortBy, String direction) {
        public Page { if(page==null) page=0; if(size==null) size=20; if(direction==null) direction="desc"; }
    }
    public record Expenses(
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDate,
        String status, @Size(max=80) String categoryCode, PaymentMethod paymentMethod,
        UUID stockDocumentId, UUID createdBy, String currencyCode, @Size(max=100) String q) {}
    public record Receipts(
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate toDate,
        UUID warehouseId, String currencyCode, Boolean includeUnknownCurrency, @Size(max=100) String q) {}
}
