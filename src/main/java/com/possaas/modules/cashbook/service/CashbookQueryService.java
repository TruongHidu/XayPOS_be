package com.possaas.modules.cashbook.service;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.cashbook.dto.*;
import com.possaas.modules.cashbook.repository.*;
import com.possaas.modules.subscription.dto.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class CashbookQueryService {
    private final CashbookAccessService access;
    private final CashbookRangePolicy ranges;
    private final CashbookFilterPolicy filters;
    private final CashbookEntryQueryRepository entries;
    private final CashbookExpenseQueryRepository expenses;
    private final CashbookReceiptQueryRepository receipts;
    public List<CashbookCategoryResponse> categories(String type) {
        access.read(false);
        return expenses.categories(filters.value(type,Set.of("INCOME","EXPENSE")));
    }
    public CashbookEnvelope<PageResponse<CashbookEntryResponse>> entries(CashbookReadRequests.Filter request,CashbookReadRequests.Page page) {
        var c=context(request);var f=filters.filter(request);
        var p=filters.page(page,"occurredAt",Set.of("occurredAt","amount","code"));
        long count=entries.count(c,f);
        return new CashbookEnvelope<>(c.meta("CASH_OCCURRED_AT",null),new PageResponse<>(entries.page(c,f,p),p.page(),p.size(),count,pages(count,p.size())));
    }
    public CashbookEnvelope<CashbookSummaryResponse> summary(CashbookReadRequests.Filter request) {
        var c=context(request);var f=filters.filter(request);
        return new CashbookEnvelope<>(c.meta("CASH_OCCURRED_AT",null),entries.summary(c,f));
    }
    public CashbookEnvelope<List<CashbookSeriesPointResponse>> series(CashbookReadRequests.Filter request,String granularity) {
        var c=context(request);var f=filters.filter(request);
        String gran=granularity==null?"DAY":filters.value(granularity,Set.of("DAY","MONTH"));
        var existing=entries.series(c,f,gran).stream().collect(Collectors.toMap(CashbookSeriesPointResponse::bucketStartDate,Function.identity()));
        var result=new ArrayList<CashbookSeriesPointResponse>();
        var cursor="MONTH".equals(gran)?c.fromDate().withDayOfMonth(1):c.fromDate();
        BigDecimal zero=BigDecimal.ZERO.setScale(2);
        while(!cursor.isAfter(c.toDate())) {
            result.add(existing.getOrDefault(cursor,new CashbookSeriesPointResponse(cursor,zero,zero,zero,0,0)));
            cursor="MONTH".equals(gran)?cursor.plusMonths(1):cursor.plusDays(1);
        }
        return new CashbookEnvelope<>(c.meta("CASH_OCCURRED_AT",gran),List.copyOf(result));
    }
    public CashbookEnvelope<PageResponse<CashbookExpenseResponse>> expenses(CashbookReadRequests.Expenses request,CashbookReadRequests.Page page) {
        var a=access.read(false);var c=ranges.range(a.restaurant(),request.fromDate(),request.toDate(),request.currencyCode());
        String status=filters.value(request.status(),Set.of("POSTED","VOIDED"));
        var p=filters.page(page,"expenseAt",Set.of("expenseAt","amount","expenseCode","createdAt"));
        long count=expenses.count(c,request,status);
        return new CashbookEnvelope<>(c.meta("EXPENSE_AT",null),new PageResponse<>(expenses.page(c,request,status,p),p.page(),p.size(),count,pages(count,p.size())));
    }
    public CashbookExpenseResponse expense(UUID id) {
        var a=access.read(false);
        return expenses.detail(a.actor().restaurantId(),id).orElseThrow(()->new ResourceNotFoundException("CASHBOOK_EXPENSE_NOT_FOUND","Expense not found"));
    }
    public CashbookEnvelope<PageResponse<CashbookStockReceiptResponse>> receipts(CashbookReadRequests.Receipts request,CashbookReadRequests.Page page) {
        var a=access.read(true);var c=ranges.range(a.restaurant(),request.fromDate(),request.toDate(),request.currencyCode());
        var p=filters.page(page,"confirmedAt",Set.of("confirmedAt","documentCode","totalCost"));
        long count=receipts.count(c,request);
        return new CashbookEnvelope<>(c.meta("CONFIRMED_AT",null),new PageResponse<>(receipts.page(c,request,p),p.page(),p.size(),count,pages(count,p.size())));
    }
    private CashbookQueryContext context(CashbookReadRequests.Filter request) {
        var a=access.read(false);
        return ranges.range(a.restaurant(),request.fromDate(),request.toDate(),request.currencyCode());
    }
    private static int pages(long count,int size) {return (int)Math.min(Integer.MAX_VALUE,count/size+(count%size==0?0:1));}
}
