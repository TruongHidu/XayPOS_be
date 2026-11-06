package com.possaas.modules.order.dto;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.*;
import jakarta.validation.constraints.*;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;

@Getter @Setter
public class OrderSearch {
    private OrderStatus status;
    private ServiceType serviceType;
    private UUID tableSessionId;
    @Size(max=100) private String q;
    @Min(0) private int page;
    @Min(1) @Max(100) private int size = 20;
    private String sortBy = "createdAt";
    private String direction = "desc";

    public Pageable pageable() {
        if (page < 0 || size < 1 || size > 100 || (long)page*size > Integer.MAX_VALUE || q != null && q.length()>100
                || sortBy == null || !Set.of("createdAt","updatedAt","orderCode","totalAmount").contains(sortBy)
                || direction == null || !Set.of("asc","desc").contains(direction.toLowerCase(Locale.ROOT)))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid order search options");
        return PageRequest.of(page,size,Sort.by(Sort.Direction.fromString(direction),sortBy).and(Sort.by("id")));
    }
}
