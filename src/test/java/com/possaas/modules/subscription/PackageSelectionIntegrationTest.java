package com.possaas.modules.subscription;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.dto.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.subscription.service.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No outer transaction: HTTP tests exercise real commit and rollback boundaries. */
@SpringBootTest
@AutoConfigureMockMvc
class PackageSelectionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired RestaurantRepository restaurants;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired PackageAdminService admin;
    @Autowired SubscriptionCommandService subscriptionCommands;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    private User actor;
    private final List<String> codes = new ArrayList<>();
    private final List<UUID> restaurantIds = new ArrayList<>();
    private final List<UUID> featureIds = new ArrayList<>();

    @BeforeEach void setup() { actor = user(null, "SUPER_ADMIN"); }

    @AfterEach void cleanup() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.execute("ALTER TABLE audit_logs DISABLE TRIGGER audit_logs_no_update");
            try {
                jdbc.update("DELETE FROM audit_logs WHERE actor_user_id=?", actor.getId());
                for (UUID restaurantId : restaurantIds) {
                    jdbc.update("DELETE FROM audit_logs WHERE restaurant_id=?", restaurantId);
                    jdbc.update("DELETE FROM restaurant_subscriptions WHERE restaurant_id=?", restaurantId);
                    jdbc.update("DELETE FROM users WHERE restaurant_id=?", restaurantId);
                    jdbc.update("DELETE FROM restaurants WHERE id=?", restaurantId);
                }
                for (String code : codes) {
                    jdbc.update("DELETE FROM audit_logs WHERE entity_id IN (SELECT id FROM packages WHERE code=?)", code);
                    jdbc.update("DELETE FROM packages WHERE code=?", code);
                }
                for (UUID id : featureIds) jdbc.update("DELETE FROM features WHERE id=?", id);
                jdbc.update("DELETE FROM users WHERE id=?", actor.getId());
            } finally {
                jdbc.execute("ALTER TABLE audit_logs ENABLE TRIGGER audit_logs_no_update");
            }
        });
    }

    @Test void legacyAndNullAndEmptyFeaturesRemainCompatible() throws Exception {
        for (String suffix : List.of("", ",\"features\":null", ",\"features\":[]")) {
            String code = code();
            call(post("/api/v1/admin/packages").content(body(code, suffix)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.features").isEmpty());
        }
    }

    @Test void createAndReplaceAndNoopExposeFeaturesAndAudit() throws Exception {
        String code = code();
        String features = "[{\"code\":\" staff_management \",\"limits\":{}}, {\"code\":\"QR_MENU_VIEW\"}]";
        call(post("/api/v1/admin/packages").content(body(code, ",\"maxStaff\":3,\"features\":" + features)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.features[0].code").value("QR_MENU_VIEW"))
            .andExpect(jsonPath("$.maxStaff").value(3))
            .andExpect(jsonPath("$.features[1].limits").isEmpty());
        mvc.perform(get("/api/v1/packages/" + code)).andExpect(status().isOk())
            .andExpect(jsonPath("$.maxStaff").value(3));
        call(get("/api/v1/admin/packages/" + code)).andExpect(status().isOk())
            .andExpect(jsonPath("$.maxStaff").value(3));
        assertThat(auditData(code)).contains("STAFF_MANAGEMENT", "maxStaff");
        for (String suffix : List.of("", ",\"features\":null", ",\"features\":" + features))
            call(put("/api/v1/admin/packages/" + code).content(updateBody(",\"maxStaff\":3" + suffix))).andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(2));
        assertThat(audits(code)).isEqualTo(1);
        String replacement = "[{\"code\":\"STAFF_MANAGEMENT\",\"limits\":{}},{\"code\":\"MENU_MANAGEMENT\",\"limits\":{}}]";
        call(put("/api/v1/admin/packages/" + code).content(updateBody(",\"maxStaff\":8,\"features\":" + replacement)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.features[0].code").value("MENU_MANAGEMENT"))
            .andExpect(jsonPath("$.maxStaff").value(8));
        assertThat(audits(code)).isEqualTo(2);
        assertThat(auditData(code)).contains("QR_MENU_VIEW", "MENU_MANAGEMENT");
        call(put("/api/v1/admin/packages/" + code).content(updateBody(",\"features\":[]")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.features").isEmpty());
        assertThat(audits(code)).isEqualTo(3);
        call(put("/api/v1/admin/packages/" + code).content(updateBody(",\"features\":[]"))).andExpect(status().isOk());
        assertThat(audits(code)).isEqualTo(3);
    }

    @Test void invalidSelectionsNeverCreatePartialPackage() throws Exception {
        UUID inactiveId = UUID.randomUUID(); featureIds.add(inactiveId);
        String inactive = "INACTIVE_" + inactiveId.toString().replace("-", "").toUpperCase();
        jdbc.update("INSERT INTO features(id,code,name,is_active) VALUES(?,?,?,false)", inactiveId,inactive,"Inactive");
        var bad = Map.of(
            "[{\"code\":\"MENU_MANAGEMENT\"},{\"code\":\" menu_management \"}]", "DUPLICATE_PACKAGE_FEATURE",
            "[{\"code\":\"MISSING_FEATURE\"}]", "FEATURE_NOT_FOUND",
            "[{\"code\":\"" + inactive + "\"}]", "FEATURE_DISABLED");
        for (var entry : bad.entrySet()) {
            String code = code();
            call(post("/api/v1/admin/packages").content(body(code, ",\"features\":" + entry.getKey())))
                .andExpect(status().is(entry.getValue().equals("FEATURE_NOT_FOUND") ? 404 : 400))
                .andExpect(jsonPath("$.code").value(entry.getValue()));
            assertThat(packages.findByCode(code)).isEmpty();
        }
        String code = createPackage("[{\"code\":\"MENU_MANAGEMENT\"}]");
        call(put("/api/v1/admin/packages/"+code).content(updateBody(",\"features\":[{\"code\":\"MISSING_FEATURE\"}]")))
            .andExpect(status().isNotFound());
        assertThat(admin.findByCode(code).features()).extracting(FeatureEntitlementResponse::code).containsExactly("MENU_MANAGEMENT");
        assertThat(audits(code)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"10\"", "0", "-1", "2.5", "9223372036854775808", "true", "3"})
    void invalidLimitsRejectedOnAllWritePaths(String rawValue) throws Exception {
        String feature = "{\"code\":\"STAFF_MANAGEMENT\",\"limits\":{\"maxStaff\":" + rawValue + "}}";
        String rejected = code();
        call(post("/api/v1/admin/packages").content(body(rejected, ",\"features\":[" + feature + "]")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FEATURE_LIMIT"));
        assertThat(packages.findByCode(rejected)).isEmpty();
        String code = createPackage("[]");
        call(put("/api/v1/admin/packages/"+code).content(updateBody(",\"features\":[" + feature + "]")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FEATURE_LIMIT"));
        call(post("/api/v1/admin/packages/"+code+"/features/STAFF_MANAGEMENT")
            .content("{\"limits\":{\"maxStaff\":"+rawValue+"}}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FEATURE_LIMIT"));
        assertThat(admin.findByCode(code).features()).isEmpty();
        assertThat(audits(code)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"10\"", "0", "-1", "2.5", "9223372036854775808", "true", "{}", "[]", "1.00000000000000001"})
    void invalidPackageLimitRollsBackCreateAndUpdate(String rawValue) throws Exception {
        String rejected = code();
        call(post("/api/v1/admin/packages").content(body(rejected, ",\"maxStaff\":" + rawValue)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PACKAGE_LIMIT"));
        assertThat(packages.findByCode(rejected)).isEmpty();
        String code = createPackage("[{\"code\":\"STAFF_MANAGEMENT\"}]", 4);
        call(put("/api/v1/admin/packages/" + code).content(updateBody(",\"maxStaff\":" + rawValue)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PACKAGE_LIMIT"));
        assertThat(admin.findByCode(code).maxStaff()).isEqualTo(4L);
        assertThat(audits(code)).isEqualTo(1);
    }

    @Test void packageLimitWithoutStaffFeatureDoesNotGrantStaffAccess() throws Exception {
        String code = createPackage("[]", 10);
        Restaurant restaurant = restaurant(); User owner = user(restaurant, "OWNER");
        activate(restaurant, code);
        mvc.perform(post("/api/v1/staff").with(authentication(auth(owner.getId(), restaurant.getId(), "OWNER", "STAFF_CREATE")))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name", "Cashier", "email", UUID.randomUUID()+"@example.com", "roleCode", "CASHIER",
                "initialPassword", "ExamplePassword123!"))))
            .andExpect(status().isForbidden());
    }

    @Test void unlimitedPackageAllowsMoreThanStandardLimit() throws Exception {
        String code = createPackage("[{\"code\":\"STAFF_MANAGEMENT\"}]");
        Restaurant restaurant = restaurant(); User owner = user(restaurant, "OWNER");
        var active = activate(restaurant, code);
        assertThat(active.maxStaff()).isNull();
        var snapshot = subscriptions.findById(active.id()).orElseThrow().getFeatureSnapshot();
        assertThat(snapshot).containsEntry("schemaVersion", 2).containsEntry("maxStaff", null);
        for (int i = 0; i < 4; i++) createStaff(owner, 201);
    }

    @Test void staffLimitIsPackageLevelAndUnlimitedCanBeRestored() throws Exception {
        String code=createPackage("[{\"code\":\"STAFF_MANAGEMENT\"}]", 3);
        for (var request : List.of(
            post("/api/v1/admin/packages").content(body(code(),",\"features\":[{\"code\":\"MENU_MANAGEMENT\",\"limits\":{\"maxStaff\":3}}]")),
            put("/api/v1/admin/packages/"+code).content(updateBody(",\"features\":[{\"code\":\"MENU_MANAGEMENT\",\"limits\":{\"maxStaff\":3}}]")),
            post("/api/v1/admin/packages/"+code+"/features/MENU_MANAGEMENT").content("{\"limits\":{\"maxStaff\":3}}")))
            call(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FEATURE_LIMIT"));
        call(put("/api/v1/admin/packages/"+code).content(updateBody(",\"maxStaff\":null")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.maxStaff").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.features[0].limits").isEmpty());
        call(post("/api/v1/admin/packages/"+code+"/features/QR_MENU_VIEW").content("{\"limits\":{}}"))
            .andExpect(status().isOk());
        call(post("/api/v1/admin/packages/"+code+"/features/QR_MENU_VIEW").content("{\"limits\":{}}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PACKAGE_FEATURE_ALREADY_EXISTS"));
        call(delete("/api/v1/admin/packages/"+code+"/features/QR_MENU_VIEW")).andExpect(status().isOk());
    }

    @Test void commitFailureRollsBackMetadataMappingsAndAudit() throws Exception {
        String code=code();
        Authentication missingActor=auth(UUID.randomUUID(),null,"SUPER_ADMIN","PACKAGE_MANAGE");
        mvc.perform(post("/api/v1/admin/packages").with(authentication(missingActor)).contentType(MediaType.APPLICATION_JSON)
            .content(body(code,",\"features\":[{\"code\":\"MENU_MANAGEMENT\"}]")))
            .andExpect(status().isConflict());
        assertThat(packages.findByCode(code)).isEmpty();
        String existing=createPackage("[{\"code\":\"QR_MENU_VIEW\"}]");
        mvc.perform(put("/api/v1/admin/packages/"+existing).with(authentication(missingActor)).contentType(MediaType.APPLICATION_JSON)
            .content(updateBody(",\"maxStaff\":4,\"features\":[{\"code\":\"STAFF_MANAGEMENT\"}]").replace("Test package","Changed")))
            .andExpect(status().isConflict());
        var reloaded=admin.findByCode(existing);
        assertThat(reloaded.name()).isEqualTo("Test package");
        assertThat(reloaded.maxStaff()).isNull();
        assertThat(reloaded.features()).extracting(FeatureEntitlementResponse::code).containsExactly("QR_MENU_VIEW");
        assertThat(audits(existing)).isEqualTo(1);
    }

    @Test void catalogChangesOnlyAffectFutureSnapshotsAndNeverDisableExistingStaff() throws Exception {
        String code=createPackage("[{\"code\":\"STAFF_MANAGEMENT\"}]", 2);
        Restaurant restaurant=restaurant(); User owner=user(restaurant,"OWNER");
        var active=activate(restaurant,code);
        String staff=createStaff(owner,201); createStaff(owner,201); createStaff(owner,409);
        var original=subscriptions.findById(active.id()).orElseThrow().getFeatureSnapshot();
        call(put("/api/v1/admin/packages/"+code).content(updateBody(",\"maxStaff\":1")))
            .andExpect(status().isOk());
        assertThat(subscriptions.findById(active.id()).orElseThrow().getFeatureSnapshot()).isEqualTo(original);
        subscriptionCommands.cancel(restaurant.getId(),active.id(),null,null);
        var replacement=activate(restaurant,code);
        assertThat(replacement.maxStaff()).isEqualTo(1L);
        assertThat(active.maxStaff()).isEqualTo(2L);
        assertThat(replacement.features().getFirst().limits()).isEmpty();
        assertThat(users.countActiveStaff(restaurant.getId())).isEqualTo(2);
        createStaff(owner,409);
        var ownerAuth=auth(owner.getId(),restaurant.getId(),"OWNER","STAFF_DISABLE");
        mvc.perform(patch("/api/v1/staff/"+staff+"/status").with(authentication(ownerAuth)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"active\":false,\"reason\":\"Capacity test\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/staff/"+staff+"/status").with(authentication(ownerAuth)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"active\":true}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_LIMIT_REACHED"));
    }

    @Test void basicAllowsCashiersAndEnforcesThreeStaffAfterActivation() throws Exception {
        Restaurant restaurant=restaurant(); User owner=user(restaurant,"OWNER");
        var active=activate(restaurant,"BASIC");
        assertThat(active.maxStaff()).isEqualTo(3L);
        assertThat(active.features()).extracting(FeatureEntitlementResponse::code)
            .contains("STAFF_MANAGEMENT","TABLE_MANAGEMENT","QR_MENU_VIEW",
                "STAFF_PERMISSION","KITCHEN_DISPLAY","DETAIL_REPORT","KITCHEN_TICKET_PRINT","KITCHEN_TICKET_REPRINT")
            .doesNotContain("QR_STATIC_ORDER","QR_TABLE_ORDER","RECIPE_MANAGEMENT");
        for(int i=0;i<3;i++) createStaff(owner,201);
        createStaff(owner,409);
    }

    @Test void proAddsOnlyRecipesToBasicFeaturesAndCapturesTenStaffLimit() throws Exception {
        var basic = admin.findByCode("BASIC");
        var pro = admin.findByCode("PRO");
        var basicCodes = basic.features().stream().map(FeatureEntitlementResponse::code).toList();
        var expectedPro = new ArrayList<>(basicCodes);
        expectedPro.add("RECIPE_MANAGEMENT");
        assertThat(basic.maxStaff()).isEqualTo(3L);
        assertThat(pro.maxStaff()).isEqualTo(10L);
        assertThat(pro.features()).extracting(FeatureEntitlementResponse::code)
            .containsExactlyInAnyOrderElementsOf(expectedPro);
        mvc.perform(get("/api/v1/packages/PRO")).andExpect(status().isOk())
            .andExpect(jsonPath("$.maxStaff").value(10))
            .andExpect(jsonPath("$.features[*].code").value(org.hamcrest.Matchers.hasItem("RECIPE_MANAGEMENT")));
        var active = activate(restaurant(), "PRO");
        assertThat(active.maxStaff()).isEqualTo(10L);
        assertThat(active.features()).extracting(FeatureEntitlementResponse::code)
            .containsExactlyInAnyOrderElementsOf(expectedPro);
    }

    @Test void systemAdminAndPermissionAreBothRequired() throws Exception {
        String body=body(code(),",\"features\":[]");
        mvc.perform(post("/api/v1/admin/packages").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        for (var denied:List.of(auth(actor.getId(),null,"SUPER_ADMIN"),auth(actor.getId(),null,"OWNER","PACKAGE_MANAGE"),
            auth(actor.getId(),UUID.randomUUID(),"SUPER_ADMIN","PACKAGE_MANAGE")))
            mvc.perform(post("/api/v1/admin/packages").with(authentication(denied)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        call(get("/api/v1/admin/features?includeInactive=false")).andExpect(status().isOk());
    }

    @Test void concurrentDuplicatePackageCreationHasOneWinner() throws Exception {
        String code=code(); String request=body(code,",\"features\":[{\"code\":\"MENU_MANAGEMENT\"}]");
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try {
            Callable<Integer> task=()->{ ready.countDown();start.await(10,TimeUnit.SECONDS);return call(post("/api/v1/admin/packages").content(request))
                .andReturn().getResponse().getStatus(); };
            var first=pool.submit(task);var second=pool.submit(task);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            assertThat(audits(code)).isEqualTo(1);
        } finally { start.countDown();pool.shutdownNow(); }
    }

    @Test void singleFeatureMutationWaitsForPackageLockAndPreservesCommittedMappings() throws Exception {
        String code=createPackage("[]");var pool=Executors.newFixedThreadPool(2);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);
        try {
            var first=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{
                packages.findByCodeForUpdate(code).orElseThrow();
                admin.addFeature(code,"MENU_MANAGEMENT",new PackageFeatureRequest(Map.of()),actor.getId(),null);
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Lock test timed out"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{secondStarted.countDown();return admin.addFeature(code,"QR_MENU_VIEW",new PackageFeatureRequest(Map.of()),actor.getId(),null);});
            assertThat(secondStarted.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            assertThat(admin.findByCode(code).features()).extracting(FeatureEntitlementResponse::code).containsExactly("MENU_MANAGEMENT","QR_MENU_VIEW");
        } finally {release.countDown();pool.shutdownNow();}
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth(actor.getId(),null,"SUPER_ADMIN","PACKAGE_MANAGE","PACKAGE_VIEW"))));
    }
    private Authentication auth(UUID id,UUID tenant,String role,String... permissions) {
        var principal=new CurrentUser(id,tenant,"test@example.com",role,Set.of(permissions));
        return new UsernamePasswordAuthenticationToken(principal,null,Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
    }
    private String code(){String value="PKG_"+UUID.randomUUID().toString().replace("-", "").toUpperCase();codes.add(value);return value;}
    private String body(String code,String suffix){return "{\"code\":\""+code+"\",\"name\":\"Test package\",\"priceAmount\":100,\"currencyCode\":\"VND\",\"billingCycleMonths\":1"+suffix+"}";}
    private String updateBody(String suffix){return "{\"name\":\"Test package\",\"priceAmount\":100,\"currencyCode\":\"VND\",\"billingCycleMonths\":1,\"active\":true"+suffix+"}";}
    private String createPackage(String features) throws Exception {String code=code();call(post("/api/v1/admin/packages").content(body(code,",\"features\":"+features))).andExpect(status().isCreated());return code;}
    private String createPackage(String features, long maxStaff) throws Exception {String code=code();call(post("/api/v1/admin/packages").content(body(code,",\"maxStaff\":"+maxStaff+",\"features\":"+features))).andExpect(status().isCreated());return code;}
    private long audits(String code){return jdbc.queryForObject("select count(*) from audit_logs where entity_id=(select id from packages where code=?)",Long.class,code);}
    private String auditData(String code){return jdbc.queryForList("select before_data,after_data from audit_logs where entity_id=(select id from packages where code=?)",code).toString();}
    private Restaurant restaurant(){var r=new Restaurant();r.setCode("T"+UUID.randomUUID().toString().replace("-",""));r.setName("Test");r=restaurants.saveAndFlush(r);restaurantIds.add(r.getId());return r;}
    private User user(Restaurant r,String role){var u=new User();u.setRestaurantId(r==null?null:r.getId());u.setRoleId(roles.findByCodeAndRestaurantIdIsNull(role).orElseThrow().getId());u.setName(role);u.setEmail(UUID.randomUUID()+"@example.com");u.setPasswordHash("not-used-by-test");return users.saveAndFlush(u);}
    private SubscriptionResponse activate(Restaurant r,String code){var pending=subscriptionCommands.create(r.getId(),new CreateSubscriptionRequest(code,clock.instant().minusSeconds(1),clock.instant().plusSeconds(3600),false,BigDecimal.ZERO,"VND"),null,null);return subscriptionCommands.activate(r.getId(),pending.id(),null,null);}
    private String createStaff(User owner,int expected) throws Exception {
        var response=mvc.perform(post("/api/v1/staff").with(authentication(auth(owner.getId(),owner.getRestaurantId(),"OWNER","STAFF_CREATE")))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","Cashier","email",UUID.randomUUID()+"@example.com","roleCode","CASHIER","initialPassword","ExamplePassword123!"))))
            .andExpect(status().is(expected));
        if(expected==409){response.andExpect(jsonPath("$.code").value("STAFF_LIMIT_REACHED"));return null;}
        JsonNode result=json.readTree(response.andReturn().getResponse().getContentAsString());return result.get("id").asText();
    }
}
