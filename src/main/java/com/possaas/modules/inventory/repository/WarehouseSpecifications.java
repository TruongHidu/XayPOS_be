package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.dto.WarehouseSearch;
import com.possaas.modules.inventory.entity.Warehouse;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class WarehouseSpecifications {
    private WarehouseSpecifications() {
    }

    public static Specification<Warehouse> search(UUID restaurantId, WarehouseSearch search) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("restaurantId"), restaurantId));
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (search.active() != null)
                predicates.add(cb.equal(root.get("active"), search.active()));
            if (search.q() != null && !search.q().isBlank()) {
                String pattern = "%" + escape(search.q().trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), pattern, '\\'),
                        cb.like(cb.lower(root.get("name")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
