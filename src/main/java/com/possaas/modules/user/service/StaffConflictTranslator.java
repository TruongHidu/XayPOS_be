package com.possaas.modules.user.service;
import com.possaas.common.exception.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.HttpStatus;
public final class StaffConflictTranslator {
    private StaffConflictTranslator() {}
    public static BusinessException translate(Throwable failure) {
        for (Throwable cause=failure; cause!=null; cause=cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && "users_email_key".equals(violation.getConstraintName()))
                return new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email already exists");
        }
        return new BusinessException(HttpStatus.CONFLICT, "CONCURRENT_STAFF_UPDATE", "Staff changed concurrently or violates a persistence constraint");
    }
}
