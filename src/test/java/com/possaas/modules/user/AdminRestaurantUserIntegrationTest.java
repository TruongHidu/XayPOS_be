package com.possaas.modules.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import com.possaas.modules.user.service.AdminRestaurantUserQueryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRestaurantUserIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RestaurantRepository restaurants;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired AdminRestaurantUserQueryService service;
    Restaurant restaurant;
    User owner;
    User cashier;

    @BeforeEach
    void setup() {
        restaurant = restaurant();
        owner = user(restaurant.getId(), "OWNER", "Alpha Owner");
        owner.setPhone("0901234567");
        users.saveAndFlush(owner);
        cashier = user(restaurant.getId(), "CASHIER", "Beta Cashier");
        cashier.setActive(false);
        users.saveAndFlush(cashier);
    }

    @Test
    void scopesListAndDetailAndDoesNotExposeSecrets() throws Exception {
        User foreign = user(restaurant().getId(), "OWNER", "Foreign");
        user(null, "SUPER_ADMIN", "System");
        User deleted = user(restaurant.getId(), "WAITER", "Deleted");
        deleted.setDeletedAt(Instant.now());
        users.saveAndFlush(deleted);
        String body = mvc.perform(get(path()).with(authentication(admin())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(owner.getId().toString(), cashier.getId().toString())
            .doesNotContain(foreign.getId().toString(), deleted.getId().toString(), "passwordHash", "deletedAt", "token", "must-never-return");
        mvc.perform(get(path() + "/" + owner.getId()).with(authentication(admin())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role.code").value("OWNER"));
        for (UUID id : new UUID[]{foreign.getId(), deleted.getId(), UUID.randomUUID()}) {
            mvc.perform(get(path() + "/" + id).with(authentication(admin())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        }
    }

    @Test
    void filtersNameEmailPhoneRoleAndActive() throws Exception {
        for (String q : new String[]{" ALPHA ", owner.getEmail().toUpperCase(), "090123"}) {
            mvc.perform(get(path()).param("q", q).with(authentication(admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(owner.getId().toString()));
        }
        mvc.perform(get(path()).param("roleCode", " cashier ").param("active", "false").with(authentication(admin())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(cashier.getId().toString()));
        mvc.perform(get(path()).param("active", "true").with(authentication(admin())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void escapesWildcardsAndBackslashes() throws Exception {
        User special = user(restaurant.getId(), "WAITER", "Literal%_\\Value");
        for (String q : new String[]{"%", "_", "\\"}) {
            mvc.perform(get(path()).param("q", q).with(authentication(admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(special.getId().toString()));
        }
    }

    @Test
    void stablePaginationAndWhitelistedSorting() throws Exception {
        owner.setName("Same"); cashier.setName("Same");
        users.saveAndFlush(owner); users.saveAndFlush(cashier);
        String first = mvc.perform(get(path()).param("size", "1").param("sortBy", "name").param("direction", "asc").with(authentication(admin())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalPages").value(2)).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get(path()).param("size", "1").param("page", "1").param("sortBy", "name").param("direction", "asc").with(authentication(admin())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String firstId = mapper.readTree(first).path("content").get(0).path("id").asText();
        String secondId = mapper.readTree(second).path("content").get(0).path("id").asText();
        assertThat(firstId).isLessThan(secondId);
        for (String sort : new String[]{"createdAt", "email", "lastLoginAt"}) {
            mvc.perform(get(path()).param("sortBy", sort).with(authentication(admin()))).andExpect(status().isOk());
        }
        for (String[] param : new String[][]{{"sortBy", "passwordHash"}, {"direction", "invalid"}, {"page", "-1"}, {"size", "101"}, {"q", "a".repeat(101)}, {"roleCode", "a".repeat(51)}}) {
            mvc.perform(get(path()).param(param[0], param[1]).with(authentication(admin()))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void restaurantDetailContractAndMissingRestaurants() throws Exception {
        mvc.perform(get("/api/v1/admin/restaurants/" + restaurant.getId()).with(authentication(admin())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.owners[0].id").value(owner.getId().toString()))
            .andExpect(jsonPath("$.userCounts.total").value(2)).andExpect(jsonPath("$.userCounts.active").value(1))
            .andExpect(jsonPath("$.packageAssignmentState").value("AVAILABLE"));
        Restaurant deleted = restaurant(); deleted.setDeletedAt(Instant.now()); restaurants.saveAndFlush(deleted);
        for (UUID id : new UUID[]{deleted.getId(), UUID.randomUUID()}) {
            for (String suffix : new String[]{"", "/" + owner.getId()}) {
                mvc.perform(get("/api/v1/admin/restaurants/" + id + "/users" + suffix).with(authentication(admin())))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESTAURANT_NOT_FOUND"));
            }
        }
    }

    @Test
    void bothEndpointsRequireSystemScopeAndRestaurantView() throws Exception {
        for (String url : new String[]{path(), path() + "/" + owner.getId()}) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            for (String role : new String[]{"OWNER", "MANAGER", "SUPER_ADMIN"}) {
                mvc.perform(get(url).with(authentication(auth(restaurant.getId(), role, "RESTAURANT_VIEW"))))
                    .andExpect(status().isForbidden());
            }
            mvc.perform(get(url).with(authentication(auth(null, "SUPER_ADMIN", "STAFF_VIEW"))))
                .andExpect(status().isForbidden());
            mvc.perform(get(url).with(authentication(admin()))).andExpect(status().isOk());
        }
    }

    @Test
    void queryCountDoesNotGrowWithUsers() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            entityManager.flush(); entityManager.clear(); statistics.clear();
            service.search(restaurant.getId(), null, null, null, 0, 100, "createdAt", "desc");
            long small = statistics.getPrepareStatementCount();
            for (int i = 0; i < 20; i++) user(restaurant.getId(), "WAITER", "Waiter " + i);
            entityManager.flush(); entityManager.clear(); statistics.clear();
            service.search(restaurant.getId(), null, null, null, 0, 100, "createdAt", "desc");
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(small);
            assertThat(small).isEqualTo(3);
        } finally { statistics.setStatisticsEnabled(previouslyEnabled); }
    }

    private Restaurant restaurant() {
        Restaurant item = new Restaurant(); item.setCode("UT" + UUID.randomUUID().toString().replace("-", "")); item.setName("User test restaurant");
        return restaurants.saveAndFlush(item);
    }
    private User user(UUID restaurantId, String role, String name) {
        User item = new User(); item.setRestaurantId(restaurantId);
        item.setRoleId(roles.findByCodeAndRestaurantIdIsNull(role).orElseThrow().getId());
        item.setName(name); item.setEmail(UUID.randomUUID() + "@example.com"); item.setPasswordHash("must-never-return");
        return users.saveAndFlush(item);
    }
    private String path() { return "/api/v1/admin/restaurants/" + restaurant.getId() + "/users"; }
    private Authentication admin() { return auth(null, "SUPER_ADMIN", "RESTAURANT_VIEW"); }
    private Authentication auth(UUID restaurantId, String role, String... permissions) {
        return new UsernamePasswordAuthenticationToken(new CurrentUser(UUID.randomUUID(), restaurantId,
            "test@example.com", role, Set.of(permissions)), "N/A",
            Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
    }
}
