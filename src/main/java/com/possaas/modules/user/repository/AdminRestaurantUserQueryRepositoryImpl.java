package com.possaas.modules.user.repository;

import com.possaas.modules.user.dto.AdminRestaurantUserResponse;
import com.possaas.modules.user.dto.AdminRoleSummaryResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminRestaurantUserQueryRepositoryImpl implements AdminRestaurantUserQueryRepository {
    private static final String SELECT = """
        select u.id as id, u.name as name, u.email as email, u.phone as phone,
               u.active as active, u.lastLoginAt as lastLoginAt,
               u.createdAt as createdAt, u.updatedAt as updatedAt,
               r.id as roleId, r.code as roleCode, r.name as roleName, r.active as roleActive
        """;
    private static final String FROM = """
          from User u, Role r
         where u.roleId = r.id and u.restaurantId = :restaurantId and u.deletedAt is null
        """;
    private final EntityManager entityManager;

    @Override
    public Page<AdminRestaurantUserResponse> search(UUID restaurantId, AdminRestaurantUserCriteria criteria) {
        // Revalidate at the repository boundary: only whitelisted identifiers enter JPQL.
        criteria = AdminRestaurantUserCriteria.from(criteria.query(), criteria.roleCode(), criteria.active(),
            criteria.page(), criteria.size(), criteria.sortBy(), criteria.direction());
        String filters = FROM;
        if (criteria.query() != null) filters += " and (lower(u.name) like :q escape '\\' or lower(u.email) like :q escape '\\' or lower(u.phone) like :q escape '\\')";
        if (criteria.roleCode() != null) filters += " and upper(r.code) = :roleCode";
        if (criteria.active() != null) filters += " and u.active = :active";
        long total = bind(entityManager.createQuery("select count(u.id) " + filters, Long.class), restaurantId, criteria).getSingleResult();
        PageRequest page = PageRequest.of(criteria.page(), criteria.size());
        if (page.getOffset() >= total) return new PageImpl<>(List.of(), page, total);
        String order = " order by u." + criteria.sortBy() + " " + criteria.direction() + ", u.id " + criteria.direction();
        List<AdminRestaurantUserResponse> content = bind(entityManager.createQuery(SELECT + filters + order, Tuple.class), restaurantId, criteria)
            .setFirstResult(Math.toIntExact(page.getOffset())).setMaxResults(criteria.size())
            .getResultList().stream().map(AdminRestaurantUserQueryRepositoryImpl::toResponse).toList();
        return new PageImpl<>(content, page, total);
    }

    @Override
    public Optional<AdminRestaurantUserResponse> findDetail(UUID restaurantId, UUID userId) {
        return entityManager.createQuery(SELECT + FROM + " and u.id = :userId", Tuple.class)
            .setParameter("restaurantId", restaurantId).setParameter("userId", userId)
            .getResultList().stream().findFirst().map(AdminRestaurantUserQueryRepositoryImpl::toResponse);
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> query, UUID restaurantId, AdminRestaurantUserCriteria criteria) {
        query.setParameter("restaurantId", restaurantId);
        if (criteria.query() != null) query.setParameter("q", "%" + criteria.query().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        if (criteria.roleCode() != null) query.setParameter("roleCode", criteria.roleCode());
        if (criteria.active() != null) query.setParameter("active", criteria.active());
        return query;
    }

    private static AdminRestaurantUserResponse toResponse(Tuple row) {
        return new AdminRestaurantUserResponse(row.get("id", UUID.class), row.get("name", String.class),
            row.get("email", String.class), row.get("phone", String.class), row.get("active", Boolean.class),
            new AdminRoleSummaryResponse(row.get("roleId", UUID.class), row.get("roleCode", String.class),
                row.get("roleName", String.class), row.get("roleActive", Boolean.class)),
            row.get("lastLoginAt", Instant.class), row.get("createdAt", Instant.class), row.get("updatedAt", Instant.class));
    }
}
