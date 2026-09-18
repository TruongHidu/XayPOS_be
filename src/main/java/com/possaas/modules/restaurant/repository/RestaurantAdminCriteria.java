package com.possaas.modules.restaurant.repository;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public record RestaurantAdminCriteria(
    String query,
    RestaurantStatus status,
    int page,
    int size,
    SortField sortBy,
    Direction direction
) {
    public static RestaurantAdminCriteria from(
        String query,
        RestaurantStatus status,
        int page,
        int size,
        String sortBy,
        String direction
    ) {
        String normalizedQuery = normalizeQuery(query);
        if (page < 0 || size < 1 || size > 100) {
            throw validation("Page must be non-negative and size must be between 1 and 100");
        }
        return new RestaurantAdminCriteria(
            normalizedQuery,
            status,
            page,
            size,
            SortField.parse(sortBy),
            Direction.parse(direction)
        );
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.trim();
        if (normalized.length() > 100) {
            throw validation("Restaurant search query must not exceed 100 characters");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static BusinessException validation(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public enum SortField {
        CREATED_AT("createdAt"),
        UPDATED_AT("updatedAt"),
        CODE("code"),
        NAME("name"),
        STATUS("status");

        private final String property;

        SortField(String property) {
            this.property = property;
        }

        public String property() {
            return property;
        }

        static SortField parse(String value) {
            String normalized = value == null ? "createdAt" : value.trim();
            for (SortField field : values()) {
                if (field.property.equals(normalized)) {
                    return field;
                }
            }
            throw validation("Unsupported restaurant sort field");
        }
    }

    public enum Direction {
        ASC,
        DESC;

        static Direction parse(String value) {
            String normalized = value == null ? "desc" : value.trim();
            try {
                return valueOf(normalized.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw validation("Sort direction must be asc or desc");
            }
        }
    }
}
