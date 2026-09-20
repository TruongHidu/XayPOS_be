package com.possaas.modules.restaurant.dto;

/** UI hint only; mutation services recheck eligibility under the restaurant lock. */
public enum PackageAssignmentState {
    AVAILABLE,
    PENDING,
    ACTIVE
}
