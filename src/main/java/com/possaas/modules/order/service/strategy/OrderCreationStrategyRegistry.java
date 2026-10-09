package com.possaas.modules.order.service.strategy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.ServiceType;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OrderCreationStrategyRegistry {
    private final Map<ServiceType,OrderCreationStrategy> strategies;
    public OrderCreationStrategyRegistry(List<OrderCreationStrategy> implementations) {
        var map=new EnumMap<ServiceType,OrderCreationStrategy>(ServiceType.class);
        for(var strategy:implementations)
            if(map.put(strategy.supportedType(),strategy)!=null) throw new IllegalStateException("Duplicate order strategy: "+strategy.supportedType());
        for(var type:ServiceType.values()) if(!map.containsKey(type)) throw new IllegalStateException("Required order strategy missing: "+type);
        strategies=Map.copyOf(map);
    }
    public OrderCreationStrategy get(ServiceType type) {
        var result=type==null?null:strategies.get(type);
        if(result==null) throw new BusinessException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_ORDER_SERVICE_TYPE","Unsupported service type");
        return result;
    }
}
