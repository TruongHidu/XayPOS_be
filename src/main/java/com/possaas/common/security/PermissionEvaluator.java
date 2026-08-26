package com.possaas.common.security;

import com.possaas.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PermissionEvaluator {
    private final CurrentUserProvider currentUserProvider;

    public boolean hasPermission(String permission) {
        CurrentUser user = currentUserProvider.getRequired();
        return user.permissions().contains(permission);
    }

    public void requirePermission(String permission) {
        if (!hasPermission(permission)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Missing permission: " + permission);
        }
    }
}
