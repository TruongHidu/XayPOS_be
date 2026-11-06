package com.possaas.modules.order.service.chain;
/** Choose exactly one: proceed downstream, or explicitly finish a replay/no-op. */
public interface OrderNext<C,R> {
    R proceed(C context);
    R complete(R result);
}
