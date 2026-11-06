package com.possaas.modules.qrorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.SQLException;
import java.time.Clock;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class QrStaffSnapshotConflictTest {
    @ParameterizedTest @ValueSource(strings={"uq_order_recipe_snapshot_line","uq_order_recipe_usage_ingredient"})
    void knownSnapshotConstraintsHaveSpecificStableConflict(String name) {
        var handler=new QrStaffErrorHandler(Clock.systemUTC());
        var failure=new DataIntegrityViolationException("duplicate",new ConstraintViolationException("duplicate",new SQLException(),name));
        var response=handler.conflict(failure);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }
}
