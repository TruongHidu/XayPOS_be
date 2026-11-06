package com.possaas.modules.qrorder.service;

import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded single-instance fixed-window limiter. Entries contain only one-way scope digests. */
@Component
public class QrRateLimiter {
    private final Clock clock; private final int mintLimit,submitLimit,maxEntries,windowSeconds;
    private final Map<String,Window> counters=new HashMap<>();
    public QrRateLimiter(Clock clock,@Value("${app.qr-order.rate-limit.mint:30}") int mintLimit,
            @Value("${app.qr-order.rate-limit.submit:30}") int submitLimit,
            @Value("${app.qr-order.rate-limit.max-entries:10000}") int maxEntries,
            @Value("${app.qr-order.rate-limit.window-seconds:60}") int windowSeconds) {
        this.clock=clock; this.mintLimit=positive(mintLimit); this.submitLimit=positive(submitLimit);
        this.maxEntries=positive(maxEntries); this.windowSeconds=positive(windowSeconds);
    }
    public void mint(String ip,String scope) { consume("mint:"+QrHashes.digest(Objects.toString(ip,"unknown")+":"+scope),mintLimit); }
    public void submit(UUID guest,String scope) { consume("submit:"+QrHashes.digest(guest+":"+scope),submitLimit); }
    public int retryAfterSeconds() { return windowSeconds; }
    private synchronized void consume(String key,int limit) {
        long now=clock.instant().getEpochSecond();
        counters.entrySet().removeIf(e->now>=e.getValue().start+windowSeconds);
        Window current=counters.get(key);
        if(current==null) {
            // Saturation rejects new buckets rather than evicting live counters and allowing bypass.
            if(counters.size()>=maxEntries) throw new QrRateLimitException();
            current=new Window(now); counters.put(key,current);
        }
        if(current.count>=limit) throw new QrRateLimitException(); current.count++;
    }
    private static int positive(int value) { if(value<1) throw new IllegalArgumentException("QR rate limits must be positive"); return value; }
    private static final class Window { final long start; int count; Window(long start) { this.start=start; } }
}
