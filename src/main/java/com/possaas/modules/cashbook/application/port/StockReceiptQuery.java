package com.possaas.modules.cashbook.application.port;
import java.util.Optional;
import java.util.UUID;
public interface StockReceiptQuery {
    Optional<Receipt> find(UUID restaurantId,UUID documentId);
    record Receipt(UUID id,String documentCode,String documentType,String status,String currencyCode) {}
}
