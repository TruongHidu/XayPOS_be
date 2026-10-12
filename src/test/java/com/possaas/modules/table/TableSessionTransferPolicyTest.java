package com.possaas.modules.table;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.table.dto.TableRequests;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.TableSessionTransferRepository;
import com.possaas.modules.table.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TableSessionTransferPolicyTest {
    private final TableSessionTransferPolicy policy=new TableSessionTransferPolicy(new TableValidationPolicy(),new TableLifecyclePolicy(),new TableSessionLifecyclePolicy());
    private final TableSessionTransferIdempotency idempotency=new TableSessionTransferIdempotency(new ObjectMapper(),mock(TableSessionTransferRepository.class));
    @Test void fingerprintOnlyDependsOnFrozenIntentAndUsesNormalizedReason() {
        UUID actor=UUID.randomUUID(), session=UUID.randomUUID(), target=UUID.randomUUID();
        var request=policy.normalize(new TableRequests.TransferSession(target,3L,"  quiet  "));
        String hash=idempotency.hash(actor,session,request); assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(idempotency.hash(actor,session,new TableRequests.TransferSession(target,3L,"quiet"))).isEqualTo(hash);
        assertThat(idempotency.hash(UUID.randomUUID(),session,request)).isNotEqualTo(hash);
        assertThat(idempotency.hash(actor,UUID.randomUUID(),request)).isNotEqualTo(hash);
        assertThat(idempotency.hash(actor,session,new TableRequests.TransferSession(UUID.randomUUID(),3L,"quiet"))).isNotEqualTo(hash);
        assertThat(idempotency.hash(actor,session,new TableRequests.TransferSession(target,4L,"quiet"))).isNotEqualTo(hash);
        assertThat(idempotency.hash(actor,session,new TableRequests.TransferSession(target,3L,"other"))).isNotEqualTo(hash);
        assertThat(policy.normalize(new TableRequests.TransferSession(target,0L,"  ")).reason()).isNull();
    }
    @Test void runtimePermissionCannotBeBypassedByOwnerOrSystemRole() {
        for(String role:List.of("OWNER","SUPER_ADMIN","WAITER"))
            assertThatThrownBy(()->policy.requirePermission(new CurrentUser(UUID.randomUUID(),UUID.randomUUID(),"test@example.test",role,Set.of("TABLE_OPEN"))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void inconsistentSourceFailsClosedButOccupiedInactiveSourceCanMove() {
        UUID tenant=UUID.randomUUID(), tableId=UUID.randomUUID(), sessionId=UUID.randomUUID();
        var source=new RestaurantTable(); source.setId(tableId); source.setRestaurantId(tenant); source.setStatus(TableStatus.INACTIVE);
        var session=new TableSession(); session.setId(sessionId); session.setRestaurantId(tenant); session.setTableId(tableId);
        policy.requireSource(session,source,List.of(session));
        for(var list:List.of(List.<TableSession>of(),List.of(session,session)))
            assertThatThrownBy(()->policy.requireSource(session,source,list)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("INCONSISTENT_TABLE_SESSION_STATE"));
        source.setDeletedAt(Instant.EPOCH); assertThatThrownBy(()->policy.requireSource(session,source,List.of(session))).isInstanceOf(BusinessException.class);
        source.setDeletedAt(null); source.setRestaurantId(UUID.randomUUID()); assertThatThrownBy(()->policy.requireSource(session,source,List.of(session))).isInstanceOf(BusinessException.class);
    }
    @Test void constraintTranslationIsStableWithoutGuessingKeyReuse() {
        for(String name:List.of("uq_table_transfer_key","uq_table_transfer_version","fk_table_transfer_target")) {
            var cause=new org.hibernate.exception.ConstraintViolationException("fixture",new java.sql.SQLException("fixture"),name);
            assertThat(TableConflictTranslator.translate(cause).getCode()).isEqualTo("CONCURRENT_TABLE_UPDATE");
        }
        assertThat(TableConflictTranslator.translate(new org.springframework.orm.ObjectOptimisticLockingFailureException(TableSession.class,UUID.randomUUID())).getCode()).isEqualTo("CONCURRENT_TABLE_UPDATE");
    }
}
