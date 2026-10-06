package com.possaas.modules.table;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.table.dto.TableQrResponse;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.service.*;
import com.possaas.modules.audit.service.AuditService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TablePolicyTest {
    private final TableValidationPolicy validation = new TableValidationPolicy();
    private final TableLifecyclePolicy lifecycle = new TableLifecyclePolicy();

    @Test
    void normalizesTextWithoutAcceptingInvalidValues() {
        assertThat(validation.code(" b01 ")).isEqualTo("B01");
        assertThat(validation.required(" Area ", 100)).isEqualTo("Area");
        assertThat(validation.optional("  ", 2000)).isNull();
        assertThat(validation.optional(null, 2000)).isNull();
        assertThatThrownBy(() -> validation.required(" ", 100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validation.code("x".repeat(51))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validation.optional("x".repeat(2001), 2000)).isInstanceOf(BusinessException.class);
    }

    @Test
    void validatesSmallintAndDisplayOrderBoundaries() {
        assertThat(validation.positiveShort(null, 4)).isEqualTo((short) 4);
        assertThat(validation.positiveShort(32767, 1)).isEqualTo(Short.MAX_VALUE);
        for (int value : List.of(-1, 0, 32768))
            assertThatThrownBy(() -> validation.positiveShort(value, 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validation.displayOrder(-1)).isInstanceOf(BusinessException.class);
    }

    @Test
    void availableConfigurationDoesNotImplyUnoccupiedOrOpenable() {
        var table = new RestaurantTable();
        assertThat(lifecycle.canOpen(table, null, false)).isTrue();
        assertThat(lifecycle.canOpen(table, null, true)).isFalse();
        assertThatThrownBy(() -> lifecycle.requireOpenable(table, null, true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("open session");
        table.setAreaId(UUID.randomUUID());
        var area = new TableArea();
        area.setActive(false);
        assertThat(lifecycle.canOpen(table, area, false)).isFalse();
        assertThatThrownBy(() -> lifecycle.requireOpenable(table, area, false)).isInstanceOf(BusinessException.class);
        table.setDeletedAt(Instant.EPOCH);
        assertThat(lifecycle.canOpen(table, area, false)).isFalse();
    }

    @Test
    void versionCheckIsStrictEvenForNoOp() {
        var table = new RestaurantTable();
        table.setVersion(2);
        lifecycle.requireVersion(table, 2L);
        for (Long version : Arrays.asList(null, -1L, 1L))
            assertThatThrownBy(() -> lifecycle.requireVersion(table, version)).isInstanceOf(BusinessException.class);
    }

    @Test
    void cancellationPreservesOpeningAndRejectsTerminalOrBackdatedTransitions() {
        var policy = new TableSessionLifecyclePolicy();
        var session = new TableSession();
        var openedAt = Instant.parse("2026-01-01T00:00:00Z");
        session.setOpenedAt(openedAt);
        assertThatThrownBy(() -> policy.cancel(session, UUID.randomUUID(), "mistake", openedAt.minusSeconds(1)))
                .isInstanceOf(BusinessException.class);
        UUID actor = UUID.randomUUID();
        policy.cancel(session, actor, "mistake", openedAt.plusSeconds(1));
        assertThat(session.getStatus()).isEqualTo(TableSessionStatus.CANCELLED);
        assertThat(session.getClosedBy()).isEqualTo(actor);
        assertThat(session.getOpenedAt()).isEqualTo(openedAt);
        assertThat(session.getCancelReason()).isEqualTo("mistake");
        assertThatThrownBy(() -> policy.requireOpen(session)).isInstanceOf(BusinessException.class);
        session.setStatus(TableSessionStatus.CLOSED);
        assertThatThrownBy(() -> policy.requireOpen(session)).isInstanceOf(BusinessException.class);
    }

    @Test
    void tokensAreRandomUrlSafeAndRotationCannotReturnTheOldToken() {
        var generator = new TableQrTokenGenerator();
        String first = generator.generate();
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
        assertThat(generator.rotate(first)).isNotEqualTo(first);
        var stuck = spy(new TableQrTokenGenerator());
        doReturn(first).when(stuck).generate();
        assertThatThrownBy(() -> stuck.rotate(first)).isInstanceOf(BusinessException.class);
    }

    @Test
    void lockFailuresUseStableConflictCodesWithoutRawExceptionDetails() {
        var optimistic = new org.springframework.orm.ObjectOptimisticLockingFailureException(RestaurantTable.class, UUID.randomUUID());
        var lock = new org.springframework.dao.CannotAcquireLockException("private database detail");
        for (RuntimeException failure : List.of(optimistic, lock)) {
            var translated = TableConflictTranslator.translate(failure);
            assertThat(translated.getCode()).isEqualTo("CONCURRENT_TABLE_UPDATE");
            assertThat(translated.getMessage()).doesNotContain("private database detail");
        }
    }

    @Test
    void auditAndDebugRepresentationNeverExposeQrMaterial() {
        var table = new RestaurantTable();
        table.setQrToken("sensitive-qr-material");
        var audit = new TableAudit(mock(AuditService.class));
        assertThat(audit.snapshot(table).toString()).doesNotContain("sensitive-qr-material", "qrToken", "qrPath", "qrTokenHash");
        assertThat(TableQrResponse.from(table).toString()).doesNotContain("sensitive-qr-material");
    }
}
