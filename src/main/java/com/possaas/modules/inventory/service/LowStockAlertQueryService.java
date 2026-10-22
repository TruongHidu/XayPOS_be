package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.inventory.dto.*;
import com.possaas.modules.inventory.repository.LowStockAlertQueryRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class LowStockAlertQueryService {
    private final InventoryAccessService access;
    private final LowStockAlertQueryRepository queries;

    public PageResponse<LowStockAlertResponse> search(LowStockAlertSearch search) {
        return queries.search(access.requireLowStock().restaurantId(),search);
    }
    public LowStockAlertResponse find(UUID id) {
        return queries.find(access.requireLowStock().restaurantId(),id).orElseThrow(LowStockAlertQueryService::notFound);
    }
    public static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("LOW_STOCK_ALERT_NOT_FOUND","Low stock alert not found");
    }
}
