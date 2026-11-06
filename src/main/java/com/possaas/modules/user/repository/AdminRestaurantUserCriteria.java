package com.possaas.modules.user.repository;

import com.possaas.common.exception.BusinessException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;

public record AdminRestaurantUserCriteria(
    String query, String roleCode, Boolean active, int page, int size, String sortBy, String direction
) {
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "name", "email", "lastLoginAt");

    public static AdminRestaurantUserCriteria from(String q, String roleCode, Boolean active,
            int page, int size, String sortBy, String direction) {
        String query = normalize(q, 100);
        String role = normalize(roleCode, 50);
        String sort = sortBy == null ? "createdAt" : sortBy.trim();
        String order = direction == null ? "desc" : direction.trim().toLowerCase(Locale.ROOT);
        if (page < 0 || size < 1 || size > 100 || !SORT_FIELDS.contains(sort)
                || !(order.equals("asc") || order.equals("desc"))) {
            throw invalid();
        }
        return new AdminRestaurantUserCriteria(query == null ? null : query.toLowerCase(Locale.ROOT),
            role == null ? null : role.toUpperCase(Locale.ROOT), active, page, size, sort, order);
    }

    private static String normalize(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw invalid();
        return normalized;
    }

    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid restaurant user search criteria");
    }
}
