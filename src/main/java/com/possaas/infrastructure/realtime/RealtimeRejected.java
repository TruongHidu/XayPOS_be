package com.possaas.infrastructure.realtime;

/** Contains only a stable public code, never a token or database exception. */
public final class RealtimeRejected extends RuntimeException {
    private final String code;
    public RealtimeRejected(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
