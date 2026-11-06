package com.possaas.modules.cashbook.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.cashbook.application.port.StockReceiptQuery;
import com.possaas.modules.cashbook.dto.*;
import com.possaas.modules.cashbook.entity.CashbookExpense;
import com.possaas.modules.cashbook.repository.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class CashbookExpenseCommandService {
    private final CashbookAccessService access;
    private final CashbookExpensePolicy policy;
    private final CashbookExpenseIdempotency idempotency;
    private final CashbookExpenseRepository expenses;
    private final CashbookCategoryRepository categories;
    private final StockReceiptQuery receipts;
    private final CashbookExpenseMapper mapper;
    private final CashbookExpenseAudit audit;
    private final Clock clock;
    public Result create(CashbookExpenseRequests.Create request,String rawKey,String ip) {
        var context=access.create(request!=null&&request.stockDocumentId()!=null);var actor=context.actor();
        var r=policy.normalize(request);String key=idempotency.key(rawKey);String hash=idempotency.fingerprint(actor.userId(),r);
        var previous=expenses.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(),key);
        if(previous.isPresent()) { idempotency.requireMatch(previous.get(),actor.userId(),hash);return new Result(mapper.map(previous.get()),true); }
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);policy.requirePaidTime(r.expenseAt(),now);
        var category=categories.findByCodeAndTypeAndActiveTrue(r.categoryCode(),"EXPENSE")
            .orElseThrow(()->new ResourceNotFoundException("CASHBOOK_CATEGORY_NOT_FOUND","Active expense category not found"));
        String currency=r.requestedCurrency()==null?policy.currency(context.restaurant().getCurrencyCode()):r.requestedCurrency();
        StockReceiptQuery.Receipt source=null;
        if(r.stockDocumentId()!=null) {
            if(!"INGREDIENT_PURCHASE".equals(r.categoryCode()))
                throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_STOCK_RECEIPT_LINK","A receipt-linked expense requires INGREDIENT_PURCHASE category");
            source=receipts.find(actor.restaurantId(),r.stockDocumentId())
                .orElseThrow(()->new ResourceNotFoundException("STOCK_DOCUMENT_NOT_FOUND","Stock receipt not found"));
            if(!"RECEIPT".equals(source.documentType())||!"CONFIRMED".equals(source.status()))
                throw new ConflictException("INVALID_STOCK_RECEIPT_LINK","Only a confirmed receipt can be linked");
            if(source.currencyCode()!=null&&!currency.equals(source.currencyCode()))
                throw new ConflictException("STOCK_RECEIPT_CURRENCY_MISMATCH","Expense and receipt currencies must match");
        }
        var expense=new CashbookExpense(actor.restaurantId(),category.getId(),r.amount(),currency,r.paymentMethod(),r.expenseAt(),r.stockDocumentId(),
            source==null?null:source.documentCode(),source==null?null:source.currencyCode(),r.referenceNo(),r.payeeName(),r.note(),key,hash,actor.userId(),now);
        expenses.saveAndFlush(expense);audit.record(actor,"CASHBOOK_EXPENSE_RECORDED",expense,null,ip);
        return new Result(mapper.map(expense),false);
    }
    public CashbookExpenseResponse voidExpense(UUID id,CashbookExpenseRequests.Void request,String ip) {
        var context=access.voidExpense();var actor=context.actor();
        var expense=expenses.findForUpdate(actor.restaurantId(),id)
            .orElseThrow(()->new ResourceNotFoundException("CASHBOOK_EXPENSE_NOT_FOUND","Expense not found"));
        if("VOIDED".equals(expense.getStatus())) return mapper.map(expense);
        if(request==null||request.expectedVersion()==null||request.expectedVersion()<0)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","expectedVersion is required");
        if(request.expectedVersion()!=expense.getVersion()) throw new ConflictException("CONCURRENT_CASHBOOK_UPDATE","Expense was updated by another request");
        String reason=policy.reason(request.reason());var before=audit.snapshot(expense);
        expense.voidExpense(actor.userId(),reason,clock.instant().truncatedTo(ChronoUnit.MICROS));
        expenses.saveAndFlush(expense);audit.record(actor,"CASHBOOK_EXPENSE_VOIDED",expense,before,ip);return mapper.map(expense);
    }
    public record Result(CashbookExpenseResponse response,boolean replay) {}
}
