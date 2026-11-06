package com.possaas.modules.user;

import static org.assertj.core.api.Assertions.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.authorization.entity.Permission;
import com.possaas.modules.subscription.domain.*;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.user.dto.*;
import com.possaas.modules.user.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StaffPolicyTest {
    private final StaffManagementPolicy management = new StaffManagementPolicy();
    private final TenantPermissionAssignmentPolicy assignment = new TenantPermissionAssignmentPolicy();
    private final StaffCapacityPolicy capacity = new StaffCapacityPolicy();

    @Test void ownerAndManagerMatrix() {
        var owner = actor("OWNER"); var manager = actor("MANAGER");
        assertThat(management.assignableRoles(owner)).containsExactlyInAnyOrder("MANAGER","WAITER","KITCHEN","CASHIER");
        assertThat(management.assignableRoles(manager)).containsExactlyInAnyOrder("WAITER","KITCHEN","CASHIER");
        for (String role : management.assignableRoles(owner)) {
            management.requireAssignable(owner, role); management.requireTarget(owner, UUID.randomUUID(), role);
        }
        for (String role : management.assignableRoles(manager)) {
            management.requireAssignable(manager, role); management.requireTarget(manager, UUID.randomUUID(), role);
        }
        assertCode(() -> management.requireAssignable(manager,"MANAGER"), "STAFF_ROLE_NOT_ALLOWED");
        assertCode(() -> management.requireTarget(manager,UUID.randomUUID(),"MANAGER"), "STAFF_TARGET_FORBIDDEN");
        assertCode(() -> management.requireTarget(owner,UUID.randomUUID(),"OWNER"), "OWNER_ACCOUNT_PROTECTED");
        assertCode(() -> management.requireTarget(manager,manager.userId(),"MANAGER"), "STAFF_SELF_MANAGEMENT_NOT_ALLOWED");
        for (String role : List.of("OWNER","SUPER_ADMIN")) assertCode(() -> management.requireAssignable(owner,role),"STAFF_ROLE_NOT_ALLOWED");
    }

    @ParameterizedTest @ValueSource(strings={"WAITER","KITCHEN","CASHIER","SUPER_ADMIN"})
    void operationalRoleCannotManageEvenWithPermission(String role) {
        assertCode(() -> management.assignableRoles(actor(role)), "STAFF_TARGET_FORBIDDEN");
    }

    @Test void requiresTenant() {
        assertCode(() -> management.assignableRoles(new CurrentUser(UUID.randomUUID(),null,"x","SUPER_ADMIN",Set.of())),"TENANT_ACCESS_DENIED");
    }

    @Test void validatesPermissionEffectsAndEscalation() {
        var p=permission("ORDER_CANCEL","ORDER",true);
        assignment.validate(Set.of(p.getCode()),Set.of(),List.of(p),Set.of(p.getCode()));
        assertCode(() -> assignment.validate(Set.of(p.getCode()),Set.of(p.getCode()),List.of(p),Set.of(p.getCode())),"PERMISSION_EFFECT_CONFLICT");
        assertCode(() -> assignment.validate(Set.of(p.getCode()),Set.of(),List.of(p),Set.of()),"PERMISSION_ESCALATION_NOT_ALLOWED");
        assertCode(() -> assignment.validate(Set.of("UNKNOWN"),Set.of(),List.of(),Set.of()),"PERMISSION_NOT_FOUND");
        p.setActive(false);
        assertCode(() -> assignment.validate(Set.of(p.getCode()),Set.of(),List.of(p),Set.of(p.getCode())),"PERMISSION_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN","AUDIT","SUBSCRIPTION","RESTAURANT_PROFILE"})
    void excludesSystemModules(String module) {
        var p=permission("SYSTEM_ACTION",module,true);
        assertThat(assignment.isAssignable(p)).isFalse();
        assertCode(() -> assignment.validate(Set.of(p.getCode()),Set.of(),List.of(p),Set.of(p.getCode())),"PERMISSION_NOT_ASSIGNABLE");
    }

    @Test void normalizesDtosAndPermissionCodesWithoutLoggingPassword() {
        var create=new CreateStaffRequest(" Alice "," ALICE@Example.COM ","  "," waiter ","Secret123!");
        assertThat(create.email()).isEqualTo("alice@example.com"); assertThat(create.name()).isEqualTo("Alice");
        assertThat(create.phone()).isNull(); assertThat(create.roleCode()).isEqualTo("WAITER");
        assertThat(create.toString()).doesNotContain("Secret123!");
        var update=new UpdateStaffRequest(" Bob "," BOB@Example.COM ","  "," kitchen ");
        assertThat(update.email()).isEqualTo("bob@example.com"); assertThat(update.phone()).isEmpty();
        assertThat(assignment.normalize(Set.of(" order_view ","ORDER_VIEW"))).containsExactly("ORDER_VIEW");
    }

    @Test void limitsUseSnapshotAndPositiveInteger() {
        capacity.requireCapacity(entitlement(Map.of()),Long.MAX_VALUE);
        capacity.requireCapacity(entitlement(Map.of("maxStaff",2)),1);
        assertCode(() -> capacity.requireCapacity(entitlement(Map.of("maxStaff",2)),2),"STAFF_LIMIT_REACHED");
        for(Object bad : List.of(0,-1,1.5,"2",true,Double.NaN,1e30)) {
            assertCode(() -> capacity.requireCapacity(entitlement(Map.of("maxStaff",bad)),0),"INVALID_STAFF_LIMIT_CONFIG");
        }
        Map<String,Object> nullLimit=new HashMap<>(); nullLimit.put("maxStaff",null);
        assertCode(() -> capacity.requireCapacity(entitlement(nullLimit),0),"INVALID_STAFF_LIMIT_CONFIG");
    }

    private static CurrentUser actor(String role) { return new CurrentUser(UUID.randomUUID(),UUID.randomUUID(),"actor@example.com",role,Set.of("STAFF_CREATE")); }
    private static Permission permission(String code,String module,boolean active) {
        var p=new Permission();p.setCode(code);p.setModule(module);p.setActive(active);return p;
    }
    private static CurrentEntitlement entitlement(Map<String,Object> limits) {
        Instant now=Clock.systemUTC().instant();
        return new CurrentEntitlement(UUID.randomUUID(),UUID.randomUUID(),"PRO",SubscriptionStatus.ACTIVE,now,now.plusSeconds(100),
            List.of(new SubscriptionFeatureSnapshot.FeatureGrant("STAFF_MANAGEMENT",limits)));
    }
    private static void assertCode(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
