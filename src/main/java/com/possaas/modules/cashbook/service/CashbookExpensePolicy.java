package com.possaas.modules.cashbook.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.cashbook.dto.CashbookExpenseRequests;
import com.possaas.modules.payment.entity.PaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CashbookExpensePolicy {
    public Normalized normalize(CashbookExpenseRequests.Create r) {
        if(r==null||r.amount()==null||r.amount().signum()<=0||r.amount().stripTrailingZeros().scale()>2
                ||r.amount().compareTo(new BigDecimal("9999999999999999.99"))>0)
            throw bad("INVALID_EXPENSE_AMOUNT","Expense amount must be positive with at most two decimal places");
        if(r.paymentMethod()==null||r.expenseAt()==null) throw bad("VALIDATION_ERROR","Payment method and expenseAt are required");
        return new Normalized(required(r.categoryCode(),50).toUpperCase(Locale.ROOT),r.amount().setScale(2,RoundingMode.UNNECESSARY),
            r.currencyCode()==null?null:currency(r.currencyCode()),r.paymentMethod(),r.expenseAt().truncatedTo(ChronoUnit.MICROS),
            r.stockDocumentId(),optional(r.referenceNo(),100),optional(r.payeeName(),150),optional(r.note(),10000));
    }
    public String currency(String raw) {
        String code=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);
        try { if(!code.matches("[A-Z]{3}")) throw new IllegalArgumentException(); Currency.getInstance(code);return code; }
        catch(IllegalArgumentException e) { throw bad("INVALID_CASHBOOK_CURRENCY","Currency code is invalid"); }
    }
    public void requirePaidTime(Instant expenseAt,Instant now) {
        if(expenseAt.isAfter(now)) throw bad("EXPENSE_DATE_IN_FUTURE","Expense date must not be in the future");
    }
    public String reason(String reason) { return required(reason,1000); }
    private static String required(String s,int max) {
        var result=optional(s,max);if(result==null)throw bad("VALIDATION_ERROR","Required value is missing");return result;
    }
    private static String optional(String s,int max) {
        if(s==null)return null;var value=s.trim();if(value.length()>max)throw bad("VALIDATION_ERROR","Value is too long");return value.isEmpty()?null:value;
    }
    private static BusinessException bad(String code,String message) { return new BusinessException(HttpStatus.BAD_REQUEST,code,message); }
    public record Normalized(String categoryCode,BigDecimal amount,String requestedCurrency,PaymentMethod paymentMethod,
        Instant expenseAt,UUID stockDocumentId,String referenceNo,String payeeName,String note) {}
}
