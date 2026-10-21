package com.possaas.modules.inventory.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.dto.WarehouseResponse;
import com.possaas.modules.inventory.dto.WarehouseSearch;
import com.possaas.modules.inventory.repository.WarehouseRepository;
import com.possaas.modules.inventory.repository.WarehouseSpecifications;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseQueryService {
    private static final Set<String> SORTS = Set.of("code", "name", "createdAt", "updatedAt");
    private final InventoryAccessService access;
    private final WarehouseRepository warehouses;
    private final WarehouseResponseMapper mapper;

    public PageResponse<WarehouseResponse> search(WarehouseSearch search) {
        var actor = access.require(false);
        if (!SORTS.contains(search.sortBy()))
            throw invalid("Unsupported sortBy");
        Sort.Direction direction;
        try {
            direction = Sort.Direction.fromString(search.direction());
        } catch (IllegalArgumentException failure) {
            throw invalid("Unsupported direction");
        }
        List<Sort.Order> orders = new ArrayList<>();
        orders.add(new Sort.Order(direction, search.sortBy()));
        orders.add(new Sort.Order(Sort.Direction.ASC, "id"));
        var page = warehouses.findAll(WarehouseSpecifications.search(actor.restaurantId(), search),
                PageRequest.of(search.page(), search.size(), Sort.by(orders)));
        return new PageResponse<>(page.getContent().stream().map(mapper::map).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public WarehouseResponse detail(UUID warehouseId) {
        var actor = access.require(false);
        return mapper.map(access.warehouse(actor.restaurantId(), warehouseId));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
