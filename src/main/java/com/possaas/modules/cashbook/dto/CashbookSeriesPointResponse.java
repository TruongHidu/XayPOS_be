package com.possaas.modules.cashbook.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
public record CashbookSeriesPointResponse(LocalDate bucketStartDate, BigDecimal totalIncomeAmount,
    BigDecimal totalExpenseAmount, BigDecimal netCashFlowAmount, long incomeEntryCount, long expenseEntryCount) {}
