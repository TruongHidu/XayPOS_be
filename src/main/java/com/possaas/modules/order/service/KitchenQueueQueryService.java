package com.possaas.modules.order.service;

import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.order.dto.KitchenQueueItemResponse;
import com.possaas.modules.order.dto.KitchenQueueSearch;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import com.possaas.modules.subscription.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class KitchenQueueQueryService {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final FeatureAccessChecker features;
    private final OrderItemRepository items;

    @Transactional(readOnly=true)
    public PageResponse<KitchenQueueItemResponse> search(KitchenQueueSearch search) {
        var actor=users.getRequired();
        if(!actor.hasPermission("KITCHEN_VIEW")) throw new AccessDeniedException("Required kitchen permission is missing");
        var tenant=tenants.getRequiredRestaurantId(); features.requireFeature(tenant,"KITCHEN_DISPLAY");
        search.validate();
        return PageResponse.from(items.kitchenQueue(tenant,search.getStatus(),PageRequest.of(search.getPage(),search.getSize())));
    }
}
