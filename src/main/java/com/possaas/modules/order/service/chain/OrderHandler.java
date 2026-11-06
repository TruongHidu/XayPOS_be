package com.possaas.modules.order.service.chain;
@FunctionalInterface
public interface OrderHandler<C,R> {
    R handle(C context,OrderNext<C,R> next);
}
