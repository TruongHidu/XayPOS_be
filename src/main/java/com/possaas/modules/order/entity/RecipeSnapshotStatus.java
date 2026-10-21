package com.possaas.modules.order.entity;

public enum RecipeSnapshotStatus {
    CAPTURED, NO_RECIPE, FEATURE_DISABLED, LEGACY_UNAVAILABLE,
    /** Response-only: drafts do not create a snapshot row. */
    NOT_SUBMITTED
}
