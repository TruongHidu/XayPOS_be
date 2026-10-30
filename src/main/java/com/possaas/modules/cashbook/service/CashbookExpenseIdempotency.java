package com.possaas.modules.cashbook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.cashbook.entity.CashbookExpense;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CashbookExpenseIdempotency {
    private final ObjectMapper mapper;
    public String key(String key) {
        if(key==null||key.isBlank()||key.length()>100)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key is required and must be at most 100 characters");
        return key;
    }
    public String fingerprint(UUID actor,CashbookExpensePolicy.Normalized r) {
        try {
            // Preserve the request's default-currency marker, rather than today's default.
            // This permits retry after the restaurant currency changes without repricing the expense.
            var canonical=new Canonical("cashbook-expense-v1",actor,r.categoryCode(),r.amount().toPlainString(),r.requestedCurrency(),
                r.paymentMethod().name(),r.expenseAt().toString(),r.stockDocumentId(),r.referenceNo(),r.payeeName(),r.note());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("Could not fingerprint expense",failure); }
    }
    public void requireMatch(CashbookExpense previous,UUID actor,String fingerprint) {
        if(!actor.equals(previous.getCreatedBy())||!fingerprint.equals(previous.getRequestFingerprint()))
            throw new ConflictException("CASHBOOK_IDEMPOTENCY_KEY_REUSED","Idempotency key belongs to a different expense request or actor");
    }
    private record Canonical(String operation,UUID actorId,String categoryCode,String amount,String requestedCurrency,
        String paymentMethod,String expenseAt,UUID stockDocumentId,String referenceNo,String payeeName,String note) {}
}
