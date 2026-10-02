package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.dto.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RestaurantProfileCommandService {
    private final CurrentTenantProvider tenant;
    private final CurrentUserProvider currentUser;
    private final RestaurantRepository restaurants;
    private final AuditService audit;

    @Transactional
    public RestaurantProfileResponse update(UpdateRestaurantProfileRequest request, String ip) {
        UUID restaurantId = tenant.getRequiredRestaurantId();
        var r = restaurants.findByIdForUpdate(restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        if (Arrays.asList(request.name(), request.legalName(), request.phone(), request.address(),
            request.timezone(), request.currencyCode()).stream().allMatch(Objects::isNull)) {
            throw invalid("EMPTY_UPDATE_REQUEST");
        }
        Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        if (request.name() != null && request.name().trim().length() < 2) throw invalid("VALIDATION_ERROR");
        if (request.timezone() != null) {
            try { ZoneId.of(request.timezone().trim()); }
            catch (RuntimeException ex) { throw invalid("INVALID_TIMEZONE"); }
        }
        String currency = request.currencyCode() == null ? null : request.currencyCode().trim().toUpperCase(Locale.ROOT);
        if (currency != null) {
            try { if (currency.length() != 3) throw new IllegalArgumentException(); Currency.getInstance(currency); }
            catch (IllegalArgumentException ex) { throw invalid("INVALID_CURRENCY_CODE"); }
        }
        change("name", r.getName(), request.name(), r::setName, before, after);
        change("legalName", r.getLegalName(), request.legalName(), r::setLegalName, before, after);
        change("phone", r.getPhone(), request.phone(), r::setPhone, before, after);
        change("address", r.getAddress(), request.address(), r::setAddress, before, after);
        change("timezone", r.getTimezone(), request.timezone(), r::setTimezone, before, after);
        change("currencyCode", r.getCurrencyCode(), currency, r::setCurrencyCode, before, after);
        if (!after.isEmpty()) {
            restaurants.saveAndFlush(r);
            audit.record(restaurantId, currentUser.getRequired().userId(), "RESTAURANT_PROFILE_UPDATED",
                "restaurants", restaurantId, before, after, ip);
        }
        return RestaurantProfileResponse.from(r);
    }

    private static void change(String key, String old, String input, Consumer<String> setter,
        Map<String, Object> before, Map<String, Object> after) {
        if (input == null) return;
        String value = input.isBlank() ? null : input.trim();
        if (!Objects.equals(old, value)) { before.put(key, old); after.put(key, value); setter.accept(value); }
    }

    private static BusinessException invalid(String code) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, "Invalid restaurant profile update");
    }
}
