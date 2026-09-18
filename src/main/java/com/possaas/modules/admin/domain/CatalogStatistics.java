package com.possaas.modules.admin.domain;

public record CatalogStatistics(StatusCounts packages, StatusCounts features) {
    public record StatusCounts(long total, long active, long inactive) {}
}
