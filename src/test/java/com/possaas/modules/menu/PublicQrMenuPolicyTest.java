package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.infrastructure.security.PublicQrMenuRequests;
import com.possaas.modules.menu.dto.PublicMenuSearch;
import com.possaas.modules.menu.entity.ItemGroup;
import com.possaas.modules.menu.service.PublicMenuVisibilityPolicy;
import com.possaas.modules.menu.service.PublicQrMenuAccessPolicy;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicQrMenuPolicyTest {
    @Test void matcherOnlyAllowsTheTwoGetRoutes() {
        for (String suffix : new String[]{"/token", "/token/items"}) {
            var request = new MockHttpServletRequest("GET", PublicQrMenuRequests.BASE_PATH + suffix);
            assertThat(PublicQrMenuRequests.matches(request)).isTrue();
            for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE", "HEAD"}) {
                request.setMethod(method);
                assertThat(PublicQrMenuRequests.matches(request)).isFalse();
            }
        }
        for (String path : new String[]{"/api/v1/menu/items", PublicQrMenuRequests.BASE_PATH,
                PublicQrMenuRequests.BASE_PATH + "/token/extra", PublicQrMenuRequests.BASE_PATH + "/token/items/extra"}) {
            assertThat(PublicQrMenuRequests.matches(new MockHttpServletRequest("GET", path))).isFalse();
        }
    }

    @Test void mapsOnlyExpectedEntitlementDenials() {
        var features = mock(FeatureAccessChecker.class);
        var policy = new PublicQrMenuAccessPolicy(features);
        var tenant = UUID.randomUUID();
        for (String code : new String[]{"FEATURE_NOT_ENTITLED", "SUBSCRIPTION_NOT_ACTIVE"}) {
            doThrow(new BusinessException(HttpStatus.FORBIDDEN, code, "private")).when(features).requireFeature(tenant, "QR_MENU_VIEW");
            assertThatThrownBy(() -> policy.requireAccess(tenant)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo("QR_MENU_UNAVAILABLE"));
        }
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(features).requireFeature(tenant, "QR_MENU_VIEW");
        assertThatThrownBy(() -> policy.requireAccess(tenant)).isSameAs(failure);
        var corrupt = new BusinessException(HttpStatus.CONFLICT, "INVALID_STAFF_LIMIT_CONFIG", "private");
        doThrow(corrupt).when(features).requireFeature(tenant, "QR_MENU_VIEW");
        assertThatThrownBy(() -> policy.requireAccess(tenant)).isSameAs(corrupt);
    }

    @Test void paginationIsBoundedAndHasStableTieBreaker() {
        var query = new PublicMenuSearch();
        assertThat(query.pageable().getPageSize()).isEqualTo(20);
        assertThat(query.pageable().getSort().toString()).isEqualTo("name: ASC,id: ASC");
        query.setSortBy("salePrice"); query.setDirection("desc");
        assertThat(query.pageable().getSort().toString()).isEqualTo("salePrice: DESC,id: ASC");
        query.setSortBy("costPrice");
        assertThatThrownBy(query::pageable).isInstanceOf(BusinessException.class);
        query.setSortBy("name"); query.setSize(101);
        assertThatThrownBy(query::pageable).isInstanceOf(BusinessException.class);
    }

    @Test void literalSearchEscapesSqlWildcards() {
        var query = new PublicMenuSearch(); query.setQ("  A%_\\  ");
        assertThat(query.pattern()).isEqualTo("%a\\%\\_\\\\%");
    }

    @Test void groupMustBeVisibleAndBelongToTenant() {
        var policy = new PublicMenuVisibilityPolicy();
        var tenant = UUID.randomUUID(); var group = new ItemGroup(); group.initialize(tenant, Instant.EPOCH);
        assertThat(policy.visibleGroup(group, tenant)).isTrue();
        assertThat(policy.visibleGroup(group, UUID.randomUUID())).isFalse();
        group.setActive(false); assertThat(policy.visibleGroup(group, tenant)).isFalse();
        group.setActive(true); group.setDeletedAt(Instant.EPOCH);
        assertThat(policy.visibleGroup(group, tenant)).isFalse();
        assertThat(policy.visibleGroup(null, tenant)).isFalse();
    }
}
