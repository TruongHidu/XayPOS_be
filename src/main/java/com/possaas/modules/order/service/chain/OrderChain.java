package com.possaas.modules.order.service.chain;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Immutable composition, with invocation-local continuation guards. No global request state. */
public final class OrderChain<C,R> {
    private final List<OrderHandler<C,R>> handlers;
    private final Function<C,R> terminal;
    public OrderChain(List<OrderHandler<C,R>> handlers,Function<C,R> terminal) {
        this.handlers=List.copyOf(handlers); this.terminal=Objects.requireNonNull(terminal);
    }
    public R execute(C context) { return invoke(0,Objects.requireNonNull(context)); }
    private R invoke(int index,C context) {
        if(index==handlers.size()) return Objects.requireNonNull(terminal.apply(context),"Chain terminal returned no result");
        final class Continuation implements OrderNext<C,R> {
            private boolean consumed;
            private R selected;
            private void consume() {
                if(consumed) throw new IllegalStateException("Order continuation may only be used once");
                consumed=true;
            }
            @Override public R proceed(C nextContext) {
                consume(); selected=invoke(index+1,Objects.requireNonNull(nextContext)); return selected;
            }
            @Override public R complete(R result) {
                consume(); selected=Objects.requireNonNull(result,"Short-circuit must return a result"); return selected;
            }
        }
        var next=new Continuation();
        R result=handlers.get(index).handle(context,next);
        if(!next.consumed || result==null || result!=next.selected)
            throw new IllegalStateException("Order handler must return its explicit continuation result");
        return result;
    }
}
