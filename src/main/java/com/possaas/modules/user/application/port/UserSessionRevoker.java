package com.possaas.modules.user.application.port;
import java.util.UUID;
public interface UserSessionRevoker { int revokeAllActiveForUser(UUID userId); }
