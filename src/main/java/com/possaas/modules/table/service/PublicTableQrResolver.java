package com.possaas.modules.table.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.table.dto.PublicQrTableContext;
import com.possaas.modules.table.entity.TableStatus;
import com.possaas.modules.table.repository.RestaurantTableRepository;
import com.possaas.modules.table.repository.TableAreaRepository;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicTableQrResolver {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final RestaurantTableRepository tables;
    private final TableAreaRepository areas;
    private final RestaurantRepository restaurants;

    public PublicQrTableContext resolve(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw unavailable();
        var table = tables.findByQrToken(token)
                .filter(t -> t.getDeletedAt() == null && t.getStatus() == TableStatus.AVAILABLE)
                .orElseThrow(PublicTableQrResolver::unavailable);
        if (table.getAreaId() != null) {
            areas.findByIdAndRestaurantId(table.getAreaId(), table.getRestaurantId())
                    .filter(a -> a.getDeletedAt() == null && a.isActive())
                    .orElseThrow(PublicTableQrResolver::unavailable);
        }
        var restaurant = restaurants.findByIdAndDeletedAtIsNull(table.getRestaurantId())
                .filter(r -> r.getStatus() == RestaurantStatus.ACTIVE)
                .orElseThrow(PublicTableQrResolver::unavailable);
        // Viewing the menu never depends on occupancy and never creates a seating session.
        return new PublicQrTableContext(restaurant.getId(), table.getId(), restaurant.getName(),
                restaurant.getCurrencyCode(), table.getCode(), table.getName());
    }

    private static ResourceNotFoundException unavailable() {
        return new ResourceNotFoundException("QR_MENU_NOT_FOUND", "Menu QR không còn khả dụng.");
    }
}
