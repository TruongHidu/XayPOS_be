package com.possaas.modules.order.dto;

import com.possaas.modules.order.entity.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class OrderRequests {
    private OrderRequests() {}
    public record Line(@NotNull UUID itemId, @NotNull @DecimalMin("0.001") @Digits(integer=9,fraction=3) BigDecimal quantity,
                       @Size(max=2000) String note) {}
    public record Create(@NotNull ServiceType serviceType, @NotNull SourceChannel sourceChannel, UUID tableSessionId,
                         @Min(1) @Max(32767) Integer guestCount, @Size(max=100) String customerName,
                         @Size(max=30) String customerPhone, @Size(max=2000) String note,
                         @NotEmpty @Size(max=100) List<@NotNull @Valid Line> items, OrderSubmissionMode submissionMode,
                         UUID tableId) {
        public Create(ServiceType serviceType, SourceChannel sourceChannel, UUID tableSessionId, Integer guestCount,
                      String customerName, String customerPhone, String note, List<Line> items,
                      OrderSubmissionMode submissionMode) {
            this(serviceType,sourceChannel,tableSessionId,guestCount,customerName,customerPhone,note,items,submissionMode,null);
        }
        public Create(ServiceType serviceType, SourceChannel sourceChannel, UUID tableSessionId, Integer guestCount,
                      String customerName, String customerPhone, String note, List<Line> items) {
            this(serviceType,sourceChannel,tableSessionId,guestCount,customerName,customerPhone,note,items,null);
        }
        public OrderSubmissionMode effectiveMode() { return submissionMode==null?OrderSubmissionMode.DRAFT:submissionMode; }
        @Override public String toString() { return "CreateOrderRequest[serviceType="+serviceType+", sourceChannel="+sourceChannel+", private fields omitted]"; }
    }
    public record Update(@NotNull @Min(0) Long expectedVersion, @Min(1) @Max(32767) Integer guestCount,
                         @Size(max=100) String customerName, @Size(max=30) String customerPhone, @Size(max=2000) String note) {}
    public record AddItems(@NotNull @Min(0) Long expectedVersion, @NotEmpty @Size(max=100) List<@NotNull @Valid Line> items,
                           OrderSubmissionMode submissionMode) {
        public AddItems(Long expectedVersion,List<Line> items) { this(expectedVersion,items,null); }
        public OrderSubmissionMode effectiveMode() { return submissionMode==null?OrderSubmissionMode.DRAFT:submissionMode; }
    }
    public record UpdateItem(@NotNull @Min(0) Long expectedVersion,
                             @DecimalMin("0.001") @Digits(integer=9,fraction=3) BigDecimal quantity, @Size(max=2000) String note) {}
    public record Version(@NotNull @Min(0) Long expectedVersion) {}
    public record Cancel(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max=1000) String reason) {}
}
