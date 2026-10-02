package com.possaas.modules.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.auth.service.RefreshTokenService;
import com.possaas.modules.authorization.repository.*;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class StaffConcurrencyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RestaurantRepository restaurants;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionService permissionService;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired RefreshTokenService refreshTokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    private final List<UUID> fixtures=new ArrayList<>();

    @AfterEach void cleanOwnedFixtures() {
        reset(audit);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            // PostgreSQL holds the table lock until commit; the trigger change is transactional.
            jdbc.execute("ALTER TABLE audit_logs DISABLE TRIGGER audit_logs_no_update");
            try {
                for(UUID id:fixtures) {
                    jdbc.update("delete from audit_logs where restaurant_id=?",id);
                    jdbc.update("delete from restaurant_subscriptions where restaurant_id=?",id);
                    jdbc.update("delete from users where restaurant_id=?",id);
                    jdbc.update("delete from restaurants where id=?",id);
                }
            } finally { jdbc.execute("ALTER TABLE audit_logs ENABLE TRIGGER audit_logs_no_update"); }
        });
    }

    @Test void concurrentSameEmailAcrossTenantsHasOneWinnerAndOneAudit() throws Exception {
        User first=fixture(null),second=fixture(null);
        String email=UUID.randomUUID()+"@example.com";
        var result=race(() -> create(first,email),() -> create(second,email));
        assertThat(result.stream().map(Result::status)).containsExactlyInAnyOrder(201,409);
        assertThat(result.stream().filter(r->r.status()==409).findFirst().orElseThrow().code()).isEqualTo("EMAIL_EXISTS");
        assertThat(jdbc.queryForObject("select count(*) from users where email=?",Long.class,email)).isEqualTo(1);
        long events=0; for(UUID id:fixtures) events+=jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code='STAFF_CREATED'",Long.class,id);
        assertThat(events).isEqualTo(1);
    }

    @Test void concurrentCreatesCannotExceedSnapshotLimit() throws Exception {
        User owner=fixture(1);
        var result=race(() -> create(owner,UUID.randomUUID()+"@example.com"),() -> create(owner,UUID.randomUUID()+"@example.com"));
        assertThat(result.stream().map(Result::status)).containsExactlyInAnyOrder(201,409);
        assertThat(result.stream().filter(r->r.status()==409).findFirst().orElseThrow().code()).isEqualTo("STAFF_LIMIT_REACHED");
        assertThat(users.countActiveStaff(owner.getRestaurantId())).isEqualTo(1);
    }

    @Test void concurrentCreateAndReactivateShareTheSameCapacityLock() throws Exception {
        User owner=fixture(1);
        User disabled=new User();disabled.setRestaurantId(owner.getRestaurantId());disabled.setRoleId(roles.findByCodeAndRestaurantIdIsNull("WAITER").orElseThrow().getId());
        disabled.setName("Disabled");disabled.setEmail(UUID.randomUUID()+"@example.com");disabled.setPasswordHash("unused-test-fixture");disabled.setActive(false);users.saveAndFlush(disabled);
        var result=race(() -> create(owner,UUID.randomUUID()+"@example.com"),
            () -> request(patch("/api/v1/staff/"+disabled.getId()+"/status").content("{\"active\":true}"),owner));
        assertThat(result.stream().filter(r->r.status()==200||r.status()==201).count()).isEqualTo(1);
        assertThat(result.stream().filter(r->r.status()==409).findFirst().orElseThrow().code()).isEqualTo("STAFF_LIMIT_REACHED");
        assertThat(users.countActiveStaff(owner.getRestaurantId())).isEqualTo(1);
    }

    @Test void auditFailureRollsBackPermissionReplacementAndRefreshRevocation() throws Exception {
        User owner=fixture(null); String email=UUID.randomUUID()+"@example.com";
        assertThat(create(owner,email).status()).isEqualTo(201);
        User staff=users.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        var refresh=refreshTokens.issue(staff.getId(),"test",null);
        String endpoint="/api/v1/staff/"+staff.getId()+"/permissions";
        doThrow(new IllegalStateException("Simulated audit failure")).when(audit).record(eq(owner.getRestaurantId()),eq(owner.getId()),
            eq("STAFF_PERMISSIONS_UPDATED"),eq("users"),eq(staff.getId()),anyMap(),anyMap(),any());
        assertThat(request(put(endpoint).content("{\"grants\":[\"ORDER_CANCEL\"],\"denies\":[\"TABLE_CLOSE\"]}"),owner).status()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from user_permissions where user_id=?",Long.class,staff.getId())).isZero();
        assertThat(jdbc.queryForObject("select revoked_at is null from auth_refresh_tokens where id=?",Boolean.class,refresh.entity().getId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code='STAFF_PERMISSIONS_UPDATED'",Long.class,owner.getRestaurantId())).isZero();
    }

    private Result create(User owner,String email) throws Exception {
        return request(post("/api/v1/staff").content(json.writeValueAsString(Map.of("name","Concurrent staff","email",email,"roleCode","WAITER","initialPassword","TestPassword123!"))),owner);
    }
    private Result request(MockHttpServletRequestBuilder request,User actor) throws Exception {
        var response=mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth(actor)))).andReturn().getResponse();
        return new Result(response.getStatus(),json.readTree(response.getContentAsString()).path("code").asText());
    }
    private List<Result> race(Callable<Result> a,Callable<Result> b) throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> {ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException();return a.call();});
            var second=executor.submit(() -> {ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException();return b.call();});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
        }
    }
    private Authentication auth(User user) {
        var codes=permissionService.getEffectivePermissionCodes(user);
        var principal=new CurrentUser(user.getId(),user.getRestaurantId(),user.getEmail(),"OWNER",codes);
        return new UsernamePasswordAuthenticationToken(principal,null,codes.stream().map(SimpleGrantedAuthority::new).toList());
    }
    private User fixture(Integer maxStaff) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            var r=new Restaurant();r.setCode("P1C"+UUID.randomUUID().toString().replace("-",""));r.setName("Concurrency fixture");restaurants.saveAndFlush(r);fixtures.add(r.getId());
            var owner=new User();owner.setRestaurantId(r.getId());owner.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
            owner.setName("Owner");owner.setEmail(UUID.randomUUID()+"@example.com");owner.setPasswordHash("unused-test-fixture");users.saveAndFlush(owner);
            var s=new RestaurantSubscription();s.setRestaurantId(r.getId());s.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
            s.setStatus(SubscriptionStatus.ACTIVE);s.setStartAt(clock.instant().minusSeconds(30));s.setEndAt(clock.instant().plusSeconds(3600));s.setPriceAmount(BigDecimal.ZERO);s.setCurrencyCode("VND");
            s.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1,"PRO",List.of(new SubscriptionFeatureSnapshot.FeatureGrant("STAFF_MANAGEMENT",maxStaff==null?Map.of():Map.of("maxStaff",maxStaff)),
                new SubscriptionFeatureSnapshot.FeatureGrant("STAFF_PERMISSION",Map.of())),clock.instant()).toMap());subscriptions.saveAndFlush(s);return owner;
        });
    }
    private record Result(int status,String code) {}
}
