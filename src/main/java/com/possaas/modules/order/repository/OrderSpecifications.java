package com.possaas.modules.order.repository;

import com.possaas.modules.order.dto.OrderSearch;
import com.possaas.modules.order.entity.Order;
import jakarta.persistence.criteria.Predicate;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;

public final class OrderSpecifications {
    private OrderSpecifications() {}
    public static Specification<Order> search(UUID tenant, OrderSearch search) {
        return (r,q,b) -> {
            var terms = new ArrayList<Predicate>(); terms.add(b.equal(r.get("restaurantId"),tenant));
            if(search.getStatus()!=null) terms.add(b.equal(r.get("status"),search.getStatus()));
            if(search.getServiceType()!=null) terms.add(b.equal(r.get("serviceType"),search.getServiceType()));
            if(search.getTableSessionId()!=null) terms.add(b.equal(r.get("tableSessionId"),search.getTableSessionId()));
            if(search.getQ()!=null && !search.getQ().isBlank()) {
                String value = search.getQ().trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
                terms.add(b.like(b.lower(r.get("orderCode")),"%"+value+"%",'\\'));
            }
            return b.and(terms.toArray(Predicate[]::new));
        };
    }
}
