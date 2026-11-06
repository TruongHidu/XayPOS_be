package com.possaas.modules.subscriptionpurchase.service;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.ConcurrencyFailureException;

/** Classifies PostgreSQL failures at the command boundary, after the failed transaction rolled back. */
final class PurchaseDbFailures {
    private PurchaseDbFailures() {}

    static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    static boolean uniqueViolation(Throwable failure) { return "23505".equals(sqlState(failure)); }

    /** PostgreSQL reports the index name for unique index violations; Hibernate exposes it as constraint name. */
    static String constraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof ConstraintViolationException violation) return violation.getConstraintName();
        return null;
    }

    static boolean violates(Throwable failure, String name) {
        String constraint = constraint(failure);
        return constraint != null && (constraint.equalsIgnoreCase(name) || constraint.toLowerCase(java.util.Locale.ROOT).endsWith("." + name));
    }

    static boolean retryable(RuntimeException failure) {
        String state = sqlState(failure);
        return failure instanceof ConcurrencyFailureException || "40001".equals(state) || "40P01".equals(state)
            || "55P03".equals(state) || "23505".equals(state);
    }
}
