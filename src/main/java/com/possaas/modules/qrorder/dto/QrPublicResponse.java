package com.possaas.modules.qrorder.dto;

import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.order.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** A guest receipt covers only the submitted lines, never a table's complete bill. */
public record QrPublicResponse(UUID requestId,QrEntryType entryType,ServiceType serviceType,
        SourceChannel sourceChannel,QrRequestStatus status,long version,boolean expired,String currencyCode,
        BigDecimal estimatedAmount,BigDecimal approvedAmount,String approvedOrderCode,Instant createdAt,
        Instant expiresAt,List<Line> items) {
    public record Line(UUID requestItemId,UUID itemId,String itemName,String unit,BigDecimal quantity,
            BigDecimal estimatedUnitPrice,BigDecimal estimatedLineTotal,String note,UUID approvedOrderItemId,
            BigDecimal approvedUnitPrice,BigDecimal approvedLineTotal,OrderItemStatus status) {
        @Override public String toString() { return "QrGuestReceiptLine[redacted]"; }
    }
    @Override public String toString() { return "QrGuestReceipt[requestId="+requestId+", status="+status+"]"; }
}
