package com.possaas.modules.user;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.security.JwtTokenService;
import com.possaas.modules.auth.service.RefreshTokenService;
import com.possaas.modules.authorization.entity.*;
import com.possaas.modules.authorization.repository.*;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.*;
import com.possaas.modules.user.service.StaffQueryService;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TenantPhaseOneIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RestaurantRepository restaurants;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired UserPermissionRepository overrides;
    @Autowired PermissionService permissionService;
    @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired PackagePlanRepository packages;
    @Autowired PasswordEncoder passwords;
    @Autowired RefreshTokenService refreshTokens;
    @Autowired JwtTokenService jwt;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired StaffQueryService query;
    Restaurant restaurant;
    User owner;
    User waiter;
    RestaurantSubscription subscription;
    private static final String PASSWORD="StaffPassword123!";

    @BeforeEach void setup() {
        restaurant=restaurant(); owner=user(restaurant,"OWNER"); waiter=user(restaurant,"WAITER");
        subscription=new RestaurantSubscription(); subscription.setRestaurantId(restaurant.getId());
        subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE); subscription.setStartAt(clock.instant().minusSeconds(60));
        subscription.setEndAt(clock.instant().plusSeconds(3600)); subscription.setPriceAmount(BigDecimal.ZERO); subscription.setCurrencyCode("VND");
        snapshot("PRO",Map.of(),true); subscriptions.saveAndFlush(subscription);
    }

    @Test void profileWorksWithoutSubscriptionAndProtectsReadOnlyFields() throws Exception {
        subscriptions.delete(subscription); subscriptions.flush();
        call(get("/api/v1/restaurants/me"),owner).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(restaurant.getId().toString()));
        User manager=user(restaurant,"MANAGER");
        call(get("/api/v1/restaurants/me"),manager).andExpect(status().isOk());
        String body="{\"name\":\" Updated \" ,\"legalName\":\"Legal\",\"phone\":\"123\",\"address\":\"Addr\",\"timezone\":\"UTC\",\"currencyCode\":\"usd\"}";
        call(patch("/api/v1/restaurants/me").content(body),owner).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated")).andExpect(jsonPath("$.currencyCode").value("USD"));
        assertThat(audits("RESTAURANT_PROFILE_UPDATED")).isEqualTo(1);
        call(patch("/api/v1/restaurants/me").content(body),owner).andExpect(status().isOk());
        assertThat(audits("RESTAURANT_PROFILE_UPDATED")).isEqualTo(1);
        call(patch("/api/v1/restaurants/me").content("{\"phone\":\" \",\"legalName\":\"\",\"address\":\"\"}"),owner)
            .andExpect(status().isOk()).andExpect(jsonPath("$.phone").isEmpty()).andExpect(jsonPath("$.legalName").isEmpty());
        for(String field:List.of("code","status","publicOrderToken","restaurantId","settings"))
            call(patch("/api/v1/restaurants/me").content("{\""+field+"\":\"bad\"}"),owner).andExpect(status().isBadRequest());
        for(String value:List.of("{}","{\"name\":\" \"}","{\"timezone\":\"No/SuchZone\"}","{\"currencyCode\":\"ZZZ\"}"))
            call(patch("/api/v1/restaurants/me").content(value),owner).andExpect(status().isBadRequest());
        var other=restaurant(); var otherOwner=user(other,"OWNER");
        call(get("/api/v1/restaurants/me"),otherOwner).andExpect(jsonPath("$.id").value(other.getId().toString()));
    }

    @ParameterizedTest @ValueSource(strings={"MANAGER","WAITER","KITCHEN","CASHIER"})
    void ownerCreatesAllowedRolesWithNormalizedCredentials(String role) throws Exception {
        String email=UUID.randomUUID()+"@example.com";
        String body=createBody(" Alice "," "+email.toUpperCase(Locale.ROOT)+" ",role.toLowerCase(Locale.ROOT));
        String response=call(post("/api/v1/staff").content(body),owner).andExpect(status().isCreated())
            .andExpect(jsonPath("$.role.code").value(role)).andExpect(jsonPath("$.email").value(email)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(PASSWORD,"password","token","deletedAt");
        User created=users.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(passwords.matches(PASSWORD,created.getPasswordHash())).isTrue();
        assertThat(audits("STAFF_CREATED")).isEqualTo(1);
        call(post("/api/v1/staff").content(body),owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_EXISTS"));
        assertThat(auditJson()).doesNotContain(PASSWORD,"initialPassword","passwordHash");
    }

    @Test void managerMatrixAndProtectedTargets() throws Exception {
        User manager=user(restaurant,"MANAGER"); User secondManager=user(restaurant,"MANAGER");
        for(String role:List.of("WAITER","KITCHEN","CASHIER"))
            call(post("/api/v1/staff").content(createBody("New",UUID.randomUUID()+"@example.com",role)),manager).andExpect(status().isCreated());
        for(String role:List.of("MANAGER","OWNER","SUPER_ADMIN"))
            call(post("/api/v1/staff").content(createBody("New",UUID.randomUUID()+"@example.com",role)),manager).andExpect(status().isBadRequest());
        call(patch(path(waiter)).content("{\"name\":\"New name\"}"),manager).andExpect(status().isOk());
        for(User target:List.of(owner,manager,secondManager)) {
            call(patch(path(target)).content("{\"name\":\"Bad\"}"),manager).andExpect(status().isForbidden());
            call(patch(path(target)+"/status").content("{\"active\":false,\"reason\":\"test\"}"),manager).andExpect(status().isForbidden());
        }
        call(patch(path(owner)).content("{\"name\":\"Bad\"}"),owner).andExpect(status().isForbidden());
        String roleBody=call(get("/api/v1/staff/roles"),manager).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(roleBody).contains("WAITER","KITCHEN","CASHIER").doesNotContain("OWNER","SUPER_ADMIN","MANAGER");
    }

    @Test void scopedListSearchPaginationAndNoSecrets() throws Exception {
        User foreign=user(restaurant(),"WAITER"); User deleted=user(restaurant,"WAITER"); deleted.setDeletedAt(clock.instant()); users.saveAndFlush(deleted);
        waiter.setName("Literal%_\\Value"); waiter.setPhone("090123"); users.saveAndFlush(waiter);
        String body=call(get("/api/v1/staff"),owner).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(owner.getId().toString(),waiter.getId().toString()).doesNotContain(foreign.getId().toString(),deleted.getId().toString(),"passwordHash","deletedAt");
        for(String q:List.of("%","_","\\","090123",waiter.getEmail().toUpperCase(Locale.ROOT)))
            call(get("/api/v1/staff").param("q",q),owner).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/v1/staff").param("roleCode"," waiter ").param("active","true"),owner).andExpect(jsonPath("$.totalElements").value(1));
        String first=call(get("/api/v1/staff").param("size","1"),owner).andReturn().getResponse().getContentAsString();
        String second=call(get("/api/v1/staff").param("size","1").param("page","1"),owner).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(first).path("content").get(0).path("id")).isNotEqualTo(json.readTree(second).path("content").get(0).path("id"));
        for(String[] param:List.of(new String[]{"sortBy","passwordHash"},new String[]{"direction","bad"},new String[]{"page","-1"},new String[]{"size","101"}))
            call(get("/api/v1/staff").param(param[0],param[1]),owner).andExpect(status().isBadRequest());
        for(User target:List.of(foreign,deleted)) {
            call(get(path(target)),owner).andExpect(status().isNotFound());
            call(patch(path(target)).content("{\"name\":\"Bad\"}"),owner).andExpect(status().isNotFound());
            call(patch(path(target)+"/status").content("{\"active\":false,\"reason\":\"test\"}"),owner).andExpect(status().isNotFound());
            call(get(path(target)+"/permissions"),owner).andExpect(status().isNotFound());
            call(put(path(target)+"/permissions").content("{\"grants\":[],\"denies\":[]}"),owner).andExpect(status().isNotFound());
        }
    }

    @Test void entitlementsAndCapacityUseSnapshot() throws Exception {
        snapshot("PRO",Map.of("maxStaff",1),true); subscriptions.saveAndFlush(subscription);
        call(post("/api/v1/staff").content(createBody("New",UUID.randomUUID()+"@example.com","WAITER")),owner)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_LIMIT_REACHED"));
        call(patch(path(waiter)+"/status").content("{\"active\":false,\"reason\":\"Left\"}"),owner).andExpect(status().isOk());
        call(post("/api/v1/staff").content(createBody("New",UUID.randomUUID()+"@example.com","WAITER")),owner).andExpect(status().isCreated());
        call(patch(path(waiter)+"/status").content("{\"active\":true}"),owner).andExpect(status().isConflict());
        snapshot("PRO",Map.of("maxStaff","bad"),true); subscriptions.saveAndFlush(subscription);
        call(patch(path(waiter)+"/status").content("{\"active\":true}"),owner).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STAFF_LIMIT_CONFIG"));
        snapshot("BASIC",Map.of(),false); subscriptions.saveAndFlush(subscription);
        call(get("/api/v1/staff"),owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FEATURE_NOT_ENTITLED"));
        subscription.setEndAt(clock.instant().minusSeconds(1)); subscriptions.saveAndFlush(subscription);
        call(get("/api/v1/staff"),owner).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_ACTIVE"));
    }

    @Test void permissionsReplaceClearAndRejectEscalation() throws Exception {
        String endpoint=path(waiter)+"/permissions";
        String body="{\"grants\":[\" order_cancel \"],\"denies\":[\"TABLE_CLOSE\"]}";
        call(put(endpoint).content(body),owner).andExpect(status().isOk()).andExpect(jsonPath("$.grants[0]").value("ORDER_CANCEL"));
        assertThat(permissionService.getEffectivePermissionCodes(waiter)).contains("ORDER_CANCEL").doesNotContain("TABLE_CLOSE");
        call(put(endpoint).content(body),owner).andExpect(status().isOk());
        assertThat(audits("STAFF_PERMISSIONS_UPDATED")).isEqualTo(1);
        call(put(endpoint).content("{\"grants\":[],\"denies\":[]}"),owner).andExpect(status().isOk()).andExpect(jsonPath("$.grants").isEmpty());
        assertThat(permissionService.getEffectivePermissionCodes(waiter)).contains("TABLE_CLOSE").doesNotContain("ORDER_CANCEL");
        for(String bad:List.of("{\"grants\":[\"ORDER_VIEW\"],\"denies\":[\"ORDER_VIEW\"]}",
            "{\"grants\":[\"UNKNOWN\"],\"denies\":[]}","{\"grants\":[\"RESTAURANT_MANAGE\"],\"denies\":[]}"))
            call(put(endpoint).content(bad),owner).andExpect(status().isBadRequest());
        assertThat(audits("STAFF_PERMISSIONS_UPDATED")).isEqualTo(2);
        User manager=user(restaurant,"MANAGER");
        override(manager,"STAFF_PERMISSION_MANAGE",PermissionEffect.GRANT);
        call(put(endpoint).content("{\"grants\":[\"PAYMENT_REFUND\"],\"denies\":[]}"),manager)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_ESCALATION_NOT_ALLOWED"));
        call(put(path(owner)+"/permissions").content(body),owner).andExpect(status().isForbidden());
        call(put(path(manager)+"/permissions").content(body),manager).andExpect(status().isForbidden());
        var another=user(restaurant,"MANAGER");
        call(put(path(another)+"/permissions").content(body),manager).andExpect(status().isForbidden());
        String catalog=call(get("/api/v1/staff/permissions"),owner).andReturn().getResponse().getContentAsString();
        assertThat(catalog).contains("ORDER_VIEW").doesNotContain("PACKAGE_MANAGE","RESTAURANT_PROFILE","AUDIT_VIEW");
    }

    @Test void roleEmailAndPermissionsRevokeSessionsButCosmeticChangesDoNot() throws Exception {
        var issued=refreshTokens.issue(waiter.getId(),"test",null); em.flush();
        call(patch(path(waiter)).content("{\"name\":\"Renamed\",\"phone\":\"123\"}"),owner).andExpect(status().isOk());
        assertThat(revoked(issued.entity().getId())).isFalse();
        override(waiter,"ORDER_CANCEL",PermissionEffect.GRANT);
        call(patch(path(waiter)).content("{\"roleCode\":\"KITCHEN\"}"),owner).andExpect(status().isOk());
        assertThat(overrides.findByIdUserId(waiter.getId())).isEmpty(); assertThat(revoked(issued.entity().getId())).isTrue();
        var second=refreshTokens.issue(waiter.getId(),"test",null); em.flush();
        call(patch(path(waiter)).content("{\"email\":\""+UUID.randomUUID()+"@example.com\"}"),owner).andExpect(status().isOk());
        assertThat(revoked(second.entity().getId())).isTrue();
        var third=refreshTokens.issue(waiter.getId(),"test",null); em.flush();
        call(put(path(waiter)+"/permissions").content("{\"grants\":[\"ORDER_CANCEL\"],\"denies\":[]}"),owner).andExpect(status().isOk());
        assertThat(revoked(third.entity().getId())).isTrue();
        long count=audits("STAFF_UPDATED");
        call(patch(path(waiter)).content("{\"name\":\"Renamed\"}"),owner).andExpect(status().isOk());
        assertThat(audits("STAFF_UPDATED")).isEqualTo(count);
    }

    @Test void disabledJwtRejectedImmediatelyAndStatusIdempotent() throws Exception {
        String token=jwt.createAccessToken(principal(waiter));
        var issued=refreshTokens.issue(waiter.getId(),"test",null); em.flush();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        for(int i=0;i<2;i++) call(patch(path(waiter)+"/status").content("{\"active\":false,\"reason\":\"Left\"}"),owner).andExpect(status().isOk());
        assertThat(audits("STAFF_DISABLED")).isEqualTo(1); assertThat(revoked(issued.entity().getId())).isTrue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+waiter.getEmail()+"\",\"password\":\""+PASSWORD+"\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\""+issued.rawToken()+"\"}"))
            .andExpect(status().isUnauthorized());
        for(int i=0;i<2;i++) call(patch(path(waiter)+"/status").content("{\"active\":true}"),owner).andExpect(status().isOk());
        assertThat(audits("STAFF_ENABLED")).isEqualTo(1);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+waiter.getEmail()+"\",\"password\":\""+PASSWORD+"\"}"))
            .andExpect(status().isOk());
    }

    @Test void authorizationAndNoTenantCannotBypassGuards() throws Exception {
        mvc.perform(get("/api/v1/staff")).andExpect(status().isUnauthorized());
        call(get("/api/v1/staff"),waiter).andExpect(status().isForbidden());
        override(waiter,"STAFF_VIEW",PermissionEffect.GRANT);
        call(get("/api/v1/staff"),waiter).andExpect(status().isForbidden());
        var principal=new CurrentUser(UUID.randomUUID(),null,"admin@example.com","SUPER_ADMIN",Set.of("RESTAURANT_PROFILE_VIEW"));
        mvc.perform(get("/api/v1/restaurants/me").with(authentication(auth(principal)))).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
        var role=roles.findByCodeAndRestaurantIdIsNull("KITCHEN").orElseThrow(); role.setActive(false);roles.saveAndFlush(role);
        call(post("/api/v1/staff").content(createBody("New",UUID.randomUUID()+"@example.com","KITCHEN")),owner).andExpect(status().isNotFound());
    }

    @Test void queryCountConstantWithStaffGrowth() {
        var stats=emf.unwrap(SessionFactory.class).getStatistics();boolean enabled=stats.isStatisticsEnabled();stats.setStatisticsEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(auth(principal(owner)));
        try {
            em.flush();em.clear();stats.clear();query.search(AdminRestaurantUserCriteria.from(null,null,null,0,100,"createdAt","desc"));
            long small=stats.getPrepareStatementCount();
            for(int i=0;i<10;i++) user(restaurant,"WAITER");
            em.flush();em.clear();stats.clear();query.search(AdminRestaurantUserCriteria.from(null,null,null,0,100,"createdAt","desc"));
            assertThat(stats.getPrepareStatementCount()).isEqualTo(small);
        } finally { stats.setStatisticsEnabled(enabled);SecurityContextHolder.clearContext(); }
    }

    private ResultActions call(MockHttpServletRequestBuilder request,User actor) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth(principal(actor)))));
    }
    private CurrentUser principal(User user) { return new CurrentUser(user.getId(),user.getRestaurantId(),user.getEmail(),roles.findById(user.getRoleId()).orElseThrow().getCode(),permissionService.getEffectivePermissionCodes(user)); }
    private Authentication auth(CurrentUser user) { return new UsernamePasswordAuthenticationToken(user,null,user.permissions().stream().map(SimpleGrantedAuthority::new).toList()); }
    private String path(User user) { return "/api/v1/staff/"+user.getId(); }
    private String createBody(String name,String email,String role) throws Exception { return json.writeValueAsString(Map.of("name",name,"email",email,"roleCode",role,"initialPassword",PASSWORD)); }
    private Restaurant restaurant() { var r=new Restaurant();r.setCode("P1"+UUID.randomUUID().toString().replace("-",""));r.setName("Phase 1");return restaurants.saveAndFlush(r); }
    private User user(Restaurant r,String role) { var u=new User();u.setRestaurantId(r.getId());u.setRoleId(roles.findByCodeAndRestaurantIdIsNull(role).orElseThrow().getId());u.setName(role);u.setEmail(UUID.randomUUID()+"@example.com");u.setPasswordHash(passwords.encode(PASSWORD));return users.saveAndFlush(u); }
    private void snapshot(String code,Map<String,Object> limits,boolean staff) {
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1,code,staff?List.of(
            new SubscriptionFeatureSnapshot.FeatureGrant("STAFF_MANAGEMENT",limits),new SubscriptionFeatureSnapshot.FeatureGrant("STAFF_PERMISSION",Map.of())):List.of(),clock.instant()).toMap());
    }
    private long audits(String action) { em.flush();return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?",Long.class,restaurant.getId(),action); }
    private String auditJson() { return jdbc.queryForList("select before_data,after_data from audit_logs where restaurant_id=?",restaurant.getId()).toString(); }
    private boolean revoked(UUID id) { return jdbc.queryForObject("select revoked_at is not null from auth_refresh_tokens where id=?",Boolean.class,id); }
    private void override(User user,String code,PermissionEffect effect) {
        var p=permissions.findByCodeIn(Set.of(code)).getFirst();var key=new UserPermissionId();key.setUserId(user.getId());key.setPermissionId(p.getId());
        var value=new UserPermission();value.setId(key);value.setEffect(effect);value.setCreatedBy(owner.getId());overrides.saveAndFlush(value);
    }
}
