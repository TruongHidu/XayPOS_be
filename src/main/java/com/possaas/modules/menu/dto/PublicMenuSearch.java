package com.possaas.modules.menu.dto;

import com.possaas.common.exception.BusinessException;
import jakarta.validation.constraints.*;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;

@Getter
@Setter
public class PublicMenuSearch {
    @Size(max = 100) private String q;
    private UUID groupId;
    @Min(0) private int page;
    @Min(1) @Max(100) private int size = 20;
    private String sortBy = "name";
    private String direction = "asc";

    public Pageable pageable() {
        if (page < 0 || size < 1 || size > 100 || q != null && q.length() > 100
                || sortBy == null || !Set.of("name", "salePrice").contains(sortBy)
                || direction == null || !Set.of("asc", "desc").contains(direction.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Điều kiện tìm kiếm không hợp lệ.");
        }
        return PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(direction), sortBy).and(Sort.by("id")));
    }

    public String pattern() {
        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
