package com.possaas.modules.restaurant.service;

import com.possaas.modules.payment.application.port.ReceivingAccountQuery;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor
public class RestaurantReceivingAccountAdapter implements ReceivingAccountQuery {
    private final RestaurantRepository restaurants;

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceivingAccount> find(UUID restaurantId) {
        return restaurants.findByIdAndDeletedAtIsNull(restaurantId)
            .flatMap(r -> r.transferAccount())
            .map(a -> new ReceivingAccount(a.bankBin(), a.accountNumber(), a.accountName()));
    }
}
