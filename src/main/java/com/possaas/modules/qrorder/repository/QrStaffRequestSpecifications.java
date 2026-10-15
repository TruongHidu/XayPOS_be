package com.possaas.modules.qrorder.repository;

import com.possaas.modules.qrorder.dto.QrStaffSearch;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.restaurant.entity.*;
import com.possaas.modules.table.entity.*;
import jakarta.persistence.criteria.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Queue scope validity is evaluated in SQL, not by a per-row service/repository loop. */
public final class QrStaffRequestSpecifications {
    private QrStaffRequestSpecifications() {}
    public static Specification<QrOrderRequest> search(UUID tenant, QrStaffSearch filter, Instant now) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("restaurantId"), tenant));
            var status = filter.getStatus() == null ? QrRequestStatus.PENDING : filter.getStatus();
            predicates.add(cb.equal(root.get("status"), status));
            if (filter.getEntryType() != null) predicates.add(cb.equal(root.get("entryType"), filter.getEntryType()));
            if (filter.getTableId() != null) predicates.add(cb.equal(root.get("tableId"), filter.getTableId()));
            if (filter.getObservedSessionId() != null) predicates.add(cb.equal(root.get("observedSessionId"), filter.getObservedSessionId()));
            if (status == QrRequestStatus.PENDING && !filter.isIncludeExpired()) {
                predicates.add(cb.greaterThan(root.get("expiresAt"), now));
                predicates.add(validCredential(root, query, cb, now));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
    private static Predicate validCredential(Root<QrOrderRequest> request, CriteriaQuery<?> query, CriteriaBuilder cb, Instant now) {
        var grantQuery = query.subquery(Integer.class); var grant = grantQuery.from(QrGuestAuthorization.class);
        grantQuery.select(cb.literal(1)).where(cb.equal(grant.get("id"), request.get("guestAuthorizationId")),
            cb.equal(grant.get("restaurantId"), request.get("restaurantId")), cb.equal(grant.get("guestId"), request.get("guestId")),
            cb.equal(grant.get("entryType"), request.get("entryType")), cb.equal(grant.get("credentialHash"), request.get("credentialHash")),
            sameNullable(cb, grant.get("tableId"), request.get("tableId")),
            sameNullable(cb, grant.get("tableAccessEpoch"), request.get("tableAccessEpoch")),
            cb.isNull(grant.get("revokedAt")), cb.greaterThan(grant.get("expiresAt"), now));

        var restaurantQuery = query.subquery(Integer.class); var restaurant = restaurantQuery.from(Restaurant.class);
        restaurantQuery.select(cb.literal(1)).where(cb.equal(restaurant.get("id"), request.get("restaurantId")),
            cb.equal(restaurant.get("status"), RestaurantStatus.ACTIVE), cb.isNull(restaurant.get("deletedAt")),
            cb.or(cb.equal(request.get("entryType"), QrEntryType.TABLE),
                cb.equal(request.get("credentialHash"), digest(cb, restaurant.get("publicOrderToken")))));

        var tableQuery = query.subquery(Integer.class); var table = tableQuery.from(RestaurantTable.class);
        var areaQuery = tableQuery.subquery(Integer.class); var area = areaQuery.from(TableArea.class);
        areaQuery.select(cb.literal(1)).where(cb.equal(area.get("id"), table.get("areaId")),
            cb.equal(area.get("restaurantId"), request.get("restaurantId")), cb.isNull(area.get("deletedAt")), cb.isTrue(area.get("active")));
        var epochQuery = tableQuery.subquery(Long.class); var epoch = epochQuery.from(QrTableAccess.class);
        epochQuery.select(epoch.get("epoch")).where(cb.equal(epoch.get("tableId"), table.get("id")),
            cb.equal(epoch.get("restaurantId"), request.get("restaurantId")));
        tableQuery.select(cb.literal(1)).where(cb.equal(table.get("id"), request.get("tableId")),
            cb.equal(table.get("restaurantId"), request.get("restaurantId")), cb.isNull(table.get("deletedAt")),
            cb.equal(table.get("status"), TableStatus.AVAILABLE), cb.equal(request.get("credentialHash"), digest(cb, table.get("qrToken"))),
            cb.or(cb.isNull(table.get("areaId")), cb.exists(areaQuery)),
            cb.equal(request.get("tableAccessEpoch"), cb.coalesce(epochQuery, 0L)));
        return cb.and(cb.exists(grantQuery), cb.exists(restaurantQuery),
            cb.or(cb.equal(request.get("entryType"), QrEntryType.RESTAURANT), cb.exists(tableQuery)));
    }
    private static Expression<String> digest(CriteriaBuilder cb, Expression<String> value) {
        // sha256(bytea) is a PostgreSQL built-in; no pgcrypto extension is required.
        return cb.function("encode", String.class,
            cb.function("sha256", byte[].class, cb.function("convert_to", byte[].class, value, cb.literal("UTF8"))), cb.literal("hex"));
    }
    private static Predicate sameNullable(CriteriaBuilder cb, Expression<?> left, Expression<?> right) {
        return cb.or(cb.equal(left, right), cb.and(cb.isNull(left), cb.isNull(right)));
    }
}
