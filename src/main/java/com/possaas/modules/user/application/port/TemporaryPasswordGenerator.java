package com.possaas.modules.user.application.port;

/** Produces a one-time password that support staff can read to a user over the phone. */
public interface TemporaryPasswordGenerator {
    String generate();
}
