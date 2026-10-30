package com.possaas.modules.cashbook.dto;
import java.util.UUID;
public record CashbookCategoryResponse(UUID id, String code, String name, String type, boolean active) {}
