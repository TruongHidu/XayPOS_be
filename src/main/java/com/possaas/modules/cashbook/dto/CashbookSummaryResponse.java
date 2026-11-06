package com.possaas.modules.cashbook.dto;
import java.math.BigDecimal;
public record CashbookSummaryResponse(BigDecimal totalIncomeAmount, BigDecimal totalExpenseAmount,
    BigDecimal netCashFlowAmount, long incomeEntryCount, long expenseEntryCount,
    BigDecimal saleIncomeAmount, BigDecimal receiptLinkedExpenseAmount, BigDecimal otherExpenseAmount) {}
