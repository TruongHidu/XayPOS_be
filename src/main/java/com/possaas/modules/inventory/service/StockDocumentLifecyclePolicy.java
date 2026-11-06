package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentStatus;
import org.springframework.stereotype.Component;

@Component
public class StockDocumentLifecyclePolicy {
    public void requireManual(StockDocument document) {
        if (document.getStockAuditId() != null)
            throw new ConflictException("STOCK_DOCUMENT_MANAGED_BY_AUDIT", "Generated document is managed by its stock audit");
    }
    public void requireVersion(StockDocument document, long expectedVersion) {
        if (document.getVersion() != expectedVersion)
            throw new ConflictException("CONCURRENT_STOCK_DOCUMENT_UPDATE", "Stock document changed concurrently");
    }

    public void requireDraft(StockDocument document) {
        if (document.getStatus() == StockDocumentStatus.CONFIRMED)
            throw new ConflictException("CONFIRMED_STOCK_DOCUMENT_IMMUTABLE", "Confirmed stock document is immutable");
        if (document.getStatus() == StockDocumentStatus.CANCELLED)
            throw new ConflictException("STOCK_DOCUMENT_CANCELLED", "Cancelled stock document is immutable");
    }

    public void requireNotEmpty(StockDocument document) {
        if (document.getLineCount() == 0)
            throw new ConflictException("STOCK_DOCUMENT_EMPTY", "Stock document must contain at least one item");
    }
}
