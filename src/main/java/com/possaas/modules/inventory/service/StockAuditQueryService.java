package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.dto.*;
import com.possaas.modules.inventory.repository.*;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class StockAuditQueryService {
    private final StockAuditAccessService access;
    private final StockAuditRepository audits;
    private final StockAuditQueryRepository queries;
    public StockAuditDetailResponse detail(UUID id){return queries.detail(access.require(false).restaurantId(),id);}
    public PageResponse<StockAuditSummaryResponse> search(StockAuditSearch search){
        var result=queries.search(access.require(false).restaurantId(),search);
        return new PageResponse<>(result.content(),search.getPage(),search.getSize(),result.totalElements(),pages(result.totalElements(),search.getSize()));
    }
    public PageResponse<StockAuditItemResponse> items(UUID id,StockAuditItemSearch search){
        var tenant=access.require(false).restaurantId();
        if(!audits.existsByIdAndRestaurantId(id,tenant))throw StockAuditPolicy.missing();
        var result=queries.items(tenant,id,search);
        return new PageResponse<>(result.content(),search.getPage(),search.getSize(),result.totalElements(),pages(result.totalElements(),search.getSize()));
    }
    private static int pages(long count,int size){return (int)((count+size-1)/size);}
}
