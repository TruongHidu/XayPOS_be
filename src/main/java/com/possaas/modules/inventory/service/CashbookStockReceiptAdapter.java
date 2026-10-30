package com.possaas.modules.inventory.service;
import com.possaas.modules.cashbook.application.port.StockReceiptQuery;
import com.possaas.modules.inventory.repository.StockDocumentRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
@Component @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
public class CashbookStockReceiptAdapter implements StockReceiptQuery {
    private final StockDocumentRepository documents;
    public Optional<Receipt> find(UUID restaurantId,UUID id) {
        return documents.findByIdAndRestaurantId(id,restaurantId).map(d->new Receipt(d.getId(),d.getDocumentCode(),d.getDocumentType().name(),d.getStatus().name(),d.getCurrencyCode()));
    }
}
