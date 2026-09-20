package com.possaas.modules.subscription.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

class SubscriptionConflictHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private final SubscriptionConflictHandler handler = new SubscriptionConflictHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void optimisticConflictHasStable409CodeAndUsesInjectedClock() {
        assertConflict(new OptimisticLockingFailureException("stale version"), "CONCURRENT_SUBSCRIPTION_UPDATE");
    }

    @Test
    void uniqueConstraintNamesIdentifyPendingAndActiveConflicts() {
        assertConflict(violation("uq_restaurant_subscriptions_one_pending"), "SUBSCRIPTION_PENDING_EXISTS");
        assertConflict(violation("uq_restaurant_subscriptions_one_active"), "SUBSCRIPTION_OVERLAP");
        assertConflict(new DataIntegrityViolationException("unknown constraint"), "CONCURRENT_SUBSCRIPTION_UPDATE");
    }

    private DataIntegrityViolationException violation(String constraint) {
        ConstraintViolationException cause = mock(ConstraintViolationException.class);
        when(cause.getConstraintName()).thenReturn(constraint);
        return new DataIntegrityViolationException("write failed", cause);
    }

    private void assertConflict(RuntimeException failure, String expectedCode) {
        var response = handler.conflict(failure);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(expectedCode);
        assertThat(response.getBody().timestamp()).isEqualTo(NOW);
    }
}
