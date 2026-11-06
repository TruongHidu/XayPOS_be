package com.possaas.modules.cashbook.dto;
public record CashbookEnvelope<T>(CashbookMeta meta, T data) {}
