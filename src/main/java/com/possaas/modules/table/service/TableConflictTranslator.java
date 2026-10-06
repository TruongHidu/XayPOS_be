package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import org.hibernate.exception.ConstraintViolationException;

public final class TableConflictTranslator {
    private TableConflictTranslator() {}

    public static ConflictException translate(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException c && c.getConstraintName() != null) {
                switch (c.getConstraintName()) {
                    case "ux_table_area_name": return new ConflictException("TABLE_AREA_NAME_EXISTS", "Area name already exists");
                    case "uq_restaurant_table_code": return new ConflictException("TABLE_CODE_EXISTS", "Table code is reserved");
                    case "uq_restaurant_table_qr": return new ConflictException("QR_TOKEN_CONFLICT", "QR token conflict; retry");
                    case "ux_table_session_open": return new ConflictException("TABLE_ALREADY_OCCUPIED", "Table already has an open session");
                    default: break;
                }
            }
        }
        return new ConflictException("CONCURRENT_TABLE_UPDATE", "Resource changed concurrently or conflicts with a persistence constraint");
    }
}
