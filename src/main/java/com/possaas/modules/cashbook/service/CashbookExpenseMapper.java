package com.possaas.modules.cashbook.service;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.cashbook.dto.CashbookExpenseResponse;
import com.possaas.modules.cashbook.entity.CashbookExpense;
import com.possaas.modules.cashbook.repository.CashbookCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class CashbookExpenseMapper {
    private final CashbookCategoryRepository categories;
    public CashbookExpenseResponse map(CashbookExpense e) {
        var c=categories.findById(e.getCategoryId()).orElseThrow(()->new ResourceNotFoundException("CASHBOOK_CATEGORY_NOT_FOUND","Expense category not found"));
        return new CashbookExpenseResponse(e.getId(),e.getExpenseCode(),c.getCode(),c.getName(),e.getAmount(),e.getCurrencyCode(),
            e.getPaymentMethod(),e.getExpenseAt(),e.getStockDocumentId(),e.getSourceDocumentCode(),e.getStockDocumentId()==null?null:e.getSourceCurrencyCode()==null?"UNKNOWN":"KNOWN",
            e.getReferenceNo(),e.getPayeeName(),e.getNote(),e.getStatus(),e.getCreatedBy(),e.getCreatedAt(),e.getUpdatedAt(),e.getVersion(),e.getVoidedBy(),e.getVoidedAt(),e.getVoidReason());
    }
}
