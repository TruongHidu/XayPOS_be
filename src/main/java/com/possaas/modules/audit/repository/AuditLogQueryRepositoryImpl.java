package com.possaas.modules.audit.repository;

import com.possaas.modules.audit.entity.AuditLog;
import com.possaas.modules.audit.query.AuditLogCriteria;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuditLogQueryRepositoryImpl implements AuditLogQueryRepository {
    private final EntityManager entityManager;

    @Override
    public Page<AuditLogQueryRow> search(AuditLogCriteria criteria, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        List<AuditLog> logs = findLogs(builder, criteria, pageable);
        long total = countLogs(builder, criteria);

        Map<UUID, RestaurantEnrichment> restaurants = findRestaurants(logs);
        Map<UUID, ActorEnrichment> actors = findActors(logs);
        List<AuditLogQueryRow> rows = logs.stream()
            .map(log -> toRow(log, restaurants, actors))
            .toList();

        return new PageImpl<>(rows, pageable, total);
    }

    private List<AuditLog> findLogs(
        CriteriaBuilder builder,
        AuditLogCriteria criteria,
        Pageable pageable
    ) {
        CriteriaQuery<AuditLog> queryDefinition = builder.createQuery(AuditLog.class);
        Root<AuditLog> audit = queryDefinition.from(AuditLog.class);
        queryDefinition
            .select(audit)
            .where(predicates(builder, audit, criteria))
            .orderBy(builder.desc(audit.get("createdAt")), builder.desc(audit.get("id")));

        TypedQuery<AuditLog> query = entityManager.createQuery(queryDefinition);
        query.setFirstResult(Math.toIntExact(pageable.getOffset()));
        query.setMaxResults(pageable.getPageSize());
        return query.getResultList();
    }

    private long countLogs(CriteriaBuilder builder, AuditLogCriteria criteria) {
        CriteriaQuery<Long> countDefinition = builder.createQuery(Long.class);
        Root<AuditLog> audit = countDefinition.from(AuditLog.class);
        countDefinition
            .select(builder.count(audit))
            .where(predicates(builder, audit, criteria));
        return entityManager.createQuery(countDefinition).getSingleResult();
    }

    private Predicate[] predicates(
        CriteriaBuilder builder,
        Root<AuditLog> audit,
        AuditLogCriteria criteria
    ) {
        List<Predicate> predicates = new ArrayList<>();
        switch (criteria.scope()) {
            case SYSTEM -> predicates.add(builder.isNull(audit.get("restaurantId")));
            case TENANT -> predicates.add(builder.isNotNull(audit.get("restaurantId")));
            case ALL -> {
                // No scope predicate.
            }
        }
        addEqual(predicates, builder, audit, "restaurantId", criteria.restaurantId());
        addEqual(predicates, builder, audit, "actorUserId", criteria.actorUserId());
        addEqual(predicates, builder, audit, "actionCode", criteria.actionCode());
        addEqual(predicates, builder, audit, "entityType", criteria.entityType());
        addEqual(predicates, builder, audit, "entityId", criteria.entityId());
        if (criteria.from() != null) {
            predicates.add(builder.greaterThanOrEqualTo(audit.get("createdAt"), criteria.from()));
        }
        if (criteria.to() != null) {
            predicates.add(builder.lessThan(audit.get("createdAt"), criteria.to()));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private void addEqual(
        List<Predicate> predicates,
        CriteriaBuilder builder,
        Root<AuditLog> root,
        String attribute,
        Object value
    ) {
        if (value != null) {
            predicates.add(builder.equal(root.get(attribute), value));
        }
    }

    private Map<UUID, RestaurantEnrichment> findRestaurants(List<AuditLog> logs) {
        Set<UUID> ids = logs.stream()
            .map(AuditLog::getRestaurantId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }

        return entityManager.createQuery(
                "select r.id, r.code, r.name from Restaurant r where r.id in :ids",
                Object[].class
            )
            .setParameter("ids", ids)
            .getResultList()
            .stream()
            .collect(Collectors.toMap(
                row -> (UUID) row[0],
                row -> new RestaurantEnrichment((String) row[1], (String) row[2])
            ));
    }

    private Map<UUID, ActorEnrichment> findActors(List<AuditLog> logs) {
        Set<UUID> ids = logs.stream()
            .map(AuditLog::getActorUserId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }

        return entityManager.createQuery(
                "select u.id, u.name, u.email from User u where u.id in :ids",
                Object[].class
            )
            .setParameter("ids", ids)
            .getResultList()
            .stream()
            .collect(Collectors.toMap(
                row -> (UUID) row[0],
                row -> new ActorEnrichment((String) row[1], (String) row[2])
            ));
    }

    private AuditLogQueryRow toRow(
        AuditLog log,
        Map<UUID, RestaurantEnrichment> restaurants,
        Map<UUID, ActorEnrichment> actors
    ) {
        RestaurantEnrichment restaurant = log.getRestaurantId() == null
            ? null
            : restaurants.get(log.getRestaurantId());
        ActorEnrichment actor = log.getActorUserId() == null
            ? null
            : actors.get(log.getActorUserId());
        return new AuditLogQueryRow(
            log.getId(),
            log.getRestaurantId(),
            restaurant == null ? null : restaurant.code(),
            restaurant == null ? null : restaurant.name(),
            log.getActorUserId(),
            actor == null ? null : actor.name(),
            actor == null ? null : actor.email(),
            log.getActionCode(),
            log.getEntityType(),
            log.getEntityId(),
            log.getBeforeData(),
            log.getAfterData(),
            log.getIpAddress(),
            log.getCreatedAt()
        );
    }

    private record RestaurantEnrichment(String code, String name) {}

    private record ActorEnrichment(String name, String email) {}
}
