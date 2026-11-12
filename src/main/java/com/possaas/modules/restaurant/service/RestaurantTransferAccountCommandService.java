package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.dto.TransferAccountResponse;
import com.possaas.modules.restaurant.dto.UpdateTransferAccountRequest;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.TransferAccount;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing where customers send money is the most sensitive restaurant setting: it locks the restaurant row
 * and audits every change, but the audit never holds the full account number.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantTransferAccountCommandService {
    static final String UPDATED = "RESTAURANT_TRANSFER_ACCOUNT_UPDATED";
    static final String CLEARED = "RESTAURANT_TRANSFER_ACCOUNT_CLEARED";

    private final CurrentTenantProvider tenant;
    private final CurrentUserProvider users;
    private final RestaurantRepository restaurants;
    private final AuditService audit;

    public TransferAccountResponse update(UpdateTransferAccountRequest request, String ip) {
        var account = TransferAccount.of(request.bankBin(), request.accountNumber(), request.accountName());
        return apply(account, UPDATED, ip);
    }

    public TransferAccountResponse clear(String ip) {
        return apply(null, CLEARED, ip);
    }

    private TransferAccountResponse apply(TransferAccount next, String action, String ip) {
        Restaurant restaurant = restaurants.findByIdForUpdate(tenant.getRequiredRestaurantId())
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        Optional<TransferAccount> previous = restaurant.transferAccount();
        if (!Objects.equals(previous.orElse(null), next)) {
            restaurant.changeTransferAccount(next);
            restaurants.saveAndFlush(restaurant);
            audit.record(restaurant.getId(), users.getRequired().userId(), action, "restaurants",
                restaurant.getId(), auditView(previous.orElse(null)), auditView(next), ip);
        }
        return TransferAccountResponse.from(restaurant.transferAccount());
    }

    static Map<String, Object> auditView(TransferAccount account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("configured", account != null);
        if (account != null) {
            view.put("bankBin", account.bankBin());
            view.put("accountNumber", account.maskedNumber());
            view.put("accountName", account.accountName());
        }
        return view;
    }
}
