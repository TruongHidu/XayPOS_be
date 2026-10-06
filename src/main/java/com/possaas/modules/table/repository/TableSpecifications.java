package com.possaas.modules.table.repository;

import com.possaas.modules.table.dto.TableSearch;
import com.possaas.modules.table.entity.*;
import jakarta.persistence.criteria.Predicate;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;

public final class TableSpecifications {
    private TableSpecifications() {}

    private static String pattern(String q) {
        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static Specification<TableArea> areas(UUID tenant, TableSearch.Area search) {
        return (r, q, b) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(b.equal(r.get("restaurantId"), tenant));
            p.add(b.isNull(r.get("deletedAt")));
            if (search.getActive() != null) p.add(b.equal(r.get("active"), search.getActive()));
            if (search.getQ() != null && !search.getQ().isBlank())
                p.add(b.like(b.lower(r.get("name")), pattern(search.getQ()), '\\'));
            return b.and(p.toArray(Predicate[]::new));
        };
    }

    public static Specification<RestaurantTable> tables(UUID tenant, TableSearch.Tables search) {
        return (r, q, b) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(b.equal(r.get("restaurantId"), tenant));
            p.add(b.isNull(r.get("deletedAt")));
            if (search.getAreaId() != null) p.add(b.equal(r.get("areaId"), search.getAreaId()));
            if (search.getStatus() != null) p.add(b.equal(r.get("status"), search.getStatus()));
            if (search.getQ() != null && !search.getQ().isBlank())
                p.add(b.or(b.like(b.lower(r.get("name")), pattern(search.getQ()), '\\'),
                        b.like(b.lower(r.get("code")), pattern(search.getQ()), '\\')));
            if (search.getOccupied() != null) {
                var sub = q.subquery(UUID.class);
                var s = sub.from(TableSession.class);
                sub.select(s.get("id")).where(b.equal(s.get("restaurantId"), tenant),
                        b.equal(s.get("tableId"), r.get("id")), b.equal(s.get("status"), TableSessionStatus.OPEN));
                p.add(search.getOccupied() ? b.exists(sub) : b.not(b.exists(sub)));
            }
            return b.and(p.toArray(Predicate[]::new));
        };
    }

    public static Specification<TableSession> sessions(UUID tenant, TableSearch.Sessions search) {
        return (r, q, b) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(b.equal(r.get("restaurantId"), tenant));
            if (search.getTableId() != null) p.add(b.equal(r.get("tableId"), search.getTableId()));
            if (search.getStatus() != null) p.add(b.equal(r.get("status"), search.getStatus()));
            if (search.getOpenedFrom() != null) p.add(b.greaterThanOrEqualTo(r.get("openedAt"), search.getOpenedFrom()));
            if (search.getOpenedTo() != null) p.add(b.lessThanOrEqualTo(r.get("openedAt"), search.getOpenedTo()));
            return b.and(p.toArray(Predicate[]::new));
        };
    }
}
