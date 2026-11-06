package com.possaas.modules.table.dto;

import com.possaas.modules.table.entity.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import static com.possaas.modules.table.service.TableValidationPolicy.invalid;

public final class TableSearch {
    private TableSearch() {}

    @Getter @Setter
    public abstract static class Page {
        @Min(0) private int page = 0;
        @Min(1) @Max(100) private int size = 20;
        private String sortBy;
        private String direction = "desc";

        public Pageable pageable(Set<String> allowed, String defaultSort) {
            String field = sortBy == null ? defaultSort : sortBy;
            if (page < 0 || size < 1 || size > 100 || !allowed.contains(field) || direction == null
                    || !Set.of("asc", "desc").contains(direction.toLowerCase(Locale.ROOT)))
                throw invalid("Invalid pagination or sorting");
            return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(direction), field).and(Sort.by("id")));
        }
    }

    @Getter @Setter
    public static class Area extends Page {
        @Size(max = 100) private String q;
        private Boolean active;
        public Pageable pageable() { return pageable(Set.of("createdAt", "updatedAt", "name", "displayOrder"), "displayOrder"); }
    }

    @Getter @Setter
    public static class Tables extends Page {
        @Size(max = 100) private String q;
        private UUID areaId;
        private TableStatus status;
        private Boolean occupied;
        public Pageable pageable() { return pageable(Set.of("createdAt", "updatedAt", "name", "code", "displayOrder", "capacity"), "displayOrder"); }
    }

    @Getter @Setter
    public static class Sessions extends Page {
        private UUID tableId;
        private TableSessionStatus status;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant openedFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private Instant openedTo;
        public Pageable pageable() {
            if (openedFrom != null && openedTo != null && openedFrom.isAfter(openedTo))
                throw invalid("openedFrom must not be after openedTo");
            return pageable(Set.of("openedAt", "createdAt", "updatedAt", "sessionCode"), "openedAt");
        }
    }
}
