package com.possaas.modules.order.dto;

import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.common.exception.BusinessException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;

@Getter @Setter
public class KitchenQueueSearch {
    private OrderItemStatus status = OrderItemStatus.PENDING;
    @Min(0) private int page;
    @Min(1) @Max(100) private int size = 20;

    public void validate() {
        if(status!=OrderItemStatus.PENDING && status!=OrderItemStatus.COOKING && status!=OrderItemStatus.READY
                || page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid kitchen queue options");
    }
}
