package com.possaas.modules.order.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class OrderAccessService {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final RestaurantRepository restaurants;
    private final FeatureAccessChecker features;
    private final OrderRepository orders;

    public CurrentUser read() {
        var tenant=tenants.getRequiredRestaurantId(); features.requireFeature(tenant,"ORDER_MANAGEMENT"); return users.getRequired();
    }
    public Locked lock() {
        var tenant=tenants.getRequiredRestaurantId();
        var restaurant=restaurants.findByIdForUpdate(tenant).orElseThrow(()->new ResourceNotFoundException("RESTAURANT_NOT_FOUND","Restaurant not found"));
        features.requireFeature(tenant,"ORDER_MANAGEMENT"); return new Locked(users.getRequired(),restaurant);
    }
    public void requireCreationFeatures(UUID tenant,ServiceType type) {
        features.requireFeature(tenant,type==ServiceType.DINE_IN?"TABLE_MANAGEMENT":"POS_QUICK_ORDER");
    }
    public Order order(UUID tenant,UUID id) {
        return orders.findByIdAndRestaurantId(id,tenant).orElseThrow(()->new ResourceNotFoundException("ORDER_NOT_FOUND","Order not found"));
    }
    public record Locked(CurrentUser actor,Restaurant restaurant) {}
}
