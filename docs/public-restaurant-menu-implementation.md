# Public restaurant menu implementation report

Verified 2026-10-07 with Java 21 and PostgreSQL 17.

URL update 2026-10-08: public endpoints now use `/api/v1/public/menu/restaurants/{menuToken}` and `/api/v1/public/menu/tables/{qrToken}`, each with its `/items` suffix. Controller mappings and security matchers share base-path constants. Old public API paths were replaced; authenticated link-management and browser QR paths are unchanged. All 70 selected backend public-menu tests and 24 web QR tests passed. Backend verification used the documented diagnostic POM excluding the existing uncompilable costPrice test. Web build still reports four pre-existing unused TypeScript declarations in the QR hooks/test; these were not changed by the URL update.

## Delivered

| Method | Endpoint | Authorization |
| --- | --- | --- |
| GET | /api/v1/public/menu/restaurants/{menuToken} | Anonymous; effective QR_MENU_VIEW snapshot grant |
| GET | /api/v1/public/menu/restaurants/{menuToken}/items | Anonymous; effective QR_MENU_VIEW snapshot grant |
| GET | /api/v1/restaurants/me/menu-link | Tenant + RESTAURANT_PROFILE_UPDATE |
| POST | /api/v1/restaurants/me/menu-link | Tenant + RESTAURANT_PROFILE_UPDATE |
| POST | /api/v1/restaurants/me/menu-link/rotate | Tenant + RESTAURANT_PROFILE_UPDATE |

Contract, headers, bodies, responses and Postman: [public-restaurant-menu-api.md](public-restaurant-menu-api.md). OpenAPI: [public-restaurant-menu-api.yaml](openapi/public-restaurant-menu-api.yaml).

Restaurant resolver uses existing public_order_token, independent of table/area/session. PublicMenuReadService reuses SQL visibility, count/paging and batch group mapping for both restaurant and table menu entry points. PublicMenuAccessPolicy shares the snapshot feature gate while the table wrapper preserves QR_MENU_* errors. Public DTOs contain only restaurant/groups or the existing public item allowlist; no costPrice, token or tenant/session details.

Menu-link initialization and rotation use the existing restaurant pessimistic write lock. Initialization is idempotent; rotation compares expectedToken under lock. Database unique conflict returns PUBLIC_MENU_TOKEN_CONFLICT; stale token/locking conflict returns CONCURRENT_MENU_LINK_UPDATE. Token save/audit share one transaction; failed audit rolls back changes. Safe audit metadata contains initialized/rotated flags, no tokens or URLs.

PublicLinkTokenGenerator preserves 32 random bytes/base64url format and is reused by registration and the existing TableQrTokenGenerator wrapper. No backfill or migration is needed; V1–V15 are unchanged. Existing token values are not changed on deployment.

## Files created

Under src/main/java/com/possaas:

```text
common/security/PublicLinkTokenGenerator.java
infrastructure/security/PublicMenuRequests.java
modules/menu/controller/PublicRestaurantMenuController.java
modules/menu/controller/PublicRestaurantMenuErrorHandler.java
modules/menu/dto/PublicRestaurantMenuResponse.java
modules/menu/service/PublicMenuAccessPolicy.java
modules/menu/service/PublicMenuReadService.java
modules/menu/service/PublicRestaurantMenuQueryService.java
modules/restaurant/controller/RestaurantMenuLinkController.java
modules/restaurant/dto/PublicRestaurantMenuContext.java
modules/restaurant/dto/RestaurantMenuLinkResponse.java
modules/restaurant/dto/RotateRestaurantMenuLinkRequest.java
modules/restaurant/service/PublicRestaurantMenuResolver.java
modules/restaurant/service/RestaurantMenuLinkCommandService.java
modules/restaurant/service/RestaurantMenuLinkQueryService.java
```

Tests and documentation:

```text
src/test/java/com/possaas/modules/menu/PublicRestaurantMenuPolicyTest.java
src/test/java/com/possaas/modules/menu/PublicRestaurantMenuIntegrationTest.java
src/test/java/com/possaas/modules/menu/PublicRestaurantMenuOpenApiTest.java
docs/public-restaurant-menu-api.md
docs/openapi/public-restaurant-menu-api.yaml
docs/public-restaurant-menu-implementation.md
```

## Files updated

```text
src/main/java/com/possaas/config/SecurityConfig.java
src/main/java/com/possaas/infrastructure/security/JwtAuthenticationFilter.java
src/main/java/com/possaas/infrastructure/security/UserAccountStatusFilter.java
src/main/java/com/possaas/infrastructure/security/TenantRestaurantStatusFilter.java
src/main/java/com/possaas/modules/audit/service/AuditDataRedactor.java
src/main/java/com/possaas/modules/menu/service/PublicMenuResponseMapper.java
src/main/java/com/possaas/modules/menu/service/PublicQrMenuAccessPolicy.java
src/main/java/com/possaas/modules/menu/service/PublicQrMenuQueryService.java
src/main/java/com/possaas/modules/restaurant/repository/RestaurantRepository.java
src/main/java/com/possaas/modules/restaurant/service/RestaurantRegistrationService.java
src/main/java/com/possaas/modules/table/service/TableQrTokenGenerator.java
src/test/java/com/possaas/modules/menu/PublicQrMenuPolicyTest.java
docs/public-qr-menu-api.md
docs/openapi/public-qr-menu-api.yaml
docs/tenant-api.md
README.md
```

Security uses one exact matcher for all four anonymous menu GET routes; private mutations are not opened. DTO toString and audit redaction cover menuToken/expectedToken/menuPath. Existing table QR JSON/security are preserved. Its documentation was corrected from details to the actual fieldErrors error property; the new controller errors use details as requested. Pre-controller security errors retain their existing fieldErrors contract.

## Verification

| Check | Result |
| --- | --- |
| Main compilation | PASS: 293 Java source files |
| New tests | PASS: 37 (29 PostgreSQL integration, 6 policy, 2 OpenAPI) |
| Table QR regression | PASS: 33 tests |
| Flyway clean migration tests | PASS: 5 tests against isolated schemas |
| Final OpenAPI/reference verification | PASS: 3 tests after documenting actual table QR fieldErrors |
| git diff --check | PASS, with only Git line-ending notices |
| Diagnostic full regression | 243 tests: 241 pass, 2 existing MenuIntegrationTest failures, no errors |
| Standard Maven test | Test compilation blocked by existing MenuPolicyTest.java:18 missing costPrice constructor argument |

The two MenuIntegrationTest assertions still expect costPrice to be excluded from the internal response and rejected on input, while the existing internal API supports it. These tests and their underlying costPrice implementation were not changed. The standard project suite is therefore not green.

The initial baseline also could not connect to localhost:5432 because the dev PostgreSQL container was stopped. Verification used a dedicated temporary PostgreSQL 17 container on localhost:15432 with disposable data and separate schemas. The dev container/database were not started or cleaned. The temporary test container is removed after verification; its data is reproducible and not retained.

A diagnostic POM outside the repo was created at:

```text
C:\Users\Dell\OneDrive\Documents\New project\.takeaway-verification\pom.xml
```

It references the real repo source/resources, outputs to target/takeaway-verification and excludes only the uncompilable **/menu/MenuPolicyTest.java from test compilation. The repository pom.xml is unchanged. The existing table QR policy test was adjusted only for the refactored policy constructor.

Example diagnostic commands, from the repo with Java 21 and a dedicated PostgreSQL available:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:15432/kiot_tay_db?currentSchema=it_takeaway_regression_20261007'
$env:SPRING_FLYWAY_SCHEMAS = 'it_takeaway_regression_20261007'
$env:SPRING_FLYWAY_DEFAULT_SCHEMA = 'it_takeaway_regression_20261007'
$env:SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA = 'it_takeaway_regression_20261007'
$env:SUBSCRIPTION_EXPIRATION_ENABLED = 'false'
$env:SUPER_ADMIN_BOOTSTRAP_ENABLED = 'false'
mvn -f 'C:\Users\Dell\OneDrive\Documents\New project\.takeaway-verification\pom.xml' test
```

Logs are in ignored target: takeaway-baseline.log, takeaway-compile.log, takeaway-focused.log, takeaway-standard.log, takeaway-regression.log and takeaway-openapi-final.log. Integration tests create/drop their own schemas and do not disable audit triggers.

## Limits

No frontend, QR image generation, ordering or payment is implemented. The web path /menu/{menuToken} is the intended frontend contract, not an automatically deployed route. Public viewing requires QR_MENU_VIEW; link management does not require a subscription. Preserve no-store and mask token URLs/request bodies at proxy/CDN/APM boundaries. Rotation affects subsequent requests, not already downloaded content.
