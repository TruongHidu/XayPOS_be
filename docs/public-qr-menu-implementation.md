# Backend public QR menu — implementation report

Verified on 2026-10-07 with Java 21 and local PostgreSQL.

URL update 2026-10-08: table menu API paths use `/api/v1/public/menu/tables/{qrToken}` and `/items`; the web client and its MSW tests use these paths. The browser route `/qr/{qrToken}` remains unchanged. Verification of both public-menu families passed 70 backend tests; web QR verification passed 24 tests. See [restaurant-menu implementation report](public-restaurant-menu-implementation.md) for the existing build/test limitations.

## Delivered

- GET `/api/v1/public/menu/tables/{qrToken}`: restaurant/table public context and visible groups.
- GET `/api/v1/public/menu/tables/{qrToken}/items`: filtered, paginated public item list.
- Token resolution is in TABLE; public menu query, access/visibility policy, mapper and DTOs are in MENU. Controllers contain no persistence/business rules.
- Existing FeatureAccessChecker/EntitlementService supplies effective snapshot access using Clock. Public reads perform no audit, session, order or subscription mutations.
- Shared exact GET matcher is used by Spring Security and all three bearer/session filters. Existing internal authorization remains unchanged.
- Public responses exclude costPrice and other internal fields. Errors are sanitized and no-store; query filtering precedes pagination and groups are batch-loaded.
- No migration: existing unique qr_token and tenant/group constraints are reused. V1–V15 and target_old were not changed.

Contract, response examples, Postman and deployment guidance: [public-qr-menu-api.md](public-qr-menu-api.md).

## Files created

Under `src/main/java/com/possaas/`:

```text
infrastructure/security/PublicQrMenuRequests.java
modules/table/dto/PublicQrTableContext.java
modules/table/service/PublicTableQrResolver.java
modules/menu/controller/PublicQrMenuController.java
modules/menu/controller/PublicQrMenuErrorHandler.java
modules/menu/dto/PublicQrMenuResponse.java
modules/menu/dto/PublicMenuItemResponse.java
modules/menu/dto/PublicMenuSearch.java
modules/menu/repository/PublicMenuSpecifications.java
modules/menu/service/PublicQrMenuAccessPolicy.java
modules/menu/service/PublicMenuVisibilityPolicy.java
modules/menu/service/PublicMenuResponseMapper.java
modules/menu/service/PublicQrMenuQueryService.java
```

Tests and documentation:

```text
src/test/java/com/possaas/modules/menu/PublicQrMenuPolicyTest.java
src/test/java/com/possaas/modules/menu/PublicQrMenuIntegrationTest.java
src/test/java/com/possaas/modules/menu/PublicQrMenuOpenApiTest.java
docs/public-qr-menu-api.md
docs/openapi/public-qr-menu-api.yaml
docs/public-qr-menu-implementation.md
```

## Existing files changed

```text
src/main/java/com/possaas/config/SecurityConfig.java
src/main/java/com/possaas/infrastructure/security/JwtAuthenticationFilter.java
src/main/java/com/possaas/infrastructure/security/UserAccountStatusFilter.java
src/main/java/com/possaas/infrastructure/security/TenantRestaurantStatusFilter.java
src/main/java/com/possaas/modules/table/repository/RestaurantTableRepository.java
src/main/resources/application.yml
docs/tenant-api.md
README.md
```

## Verification and existing blockers

| Run | Result |
| --- | --- |
| Main Java compilation | PASS, 278 source files. |
| New QR tests | PASS: 33 tests (27 PostgreSQL integration, 5 policy, 1 OpenAPI). |
| Standard `mvn test` | FAIL at test compilation: existing MenuPolicyTest.java:18 omits the newly added costPrice constructor argument. |
| Diagnostic regression, excluding only uncompilable MenuPolicyTest | 206 tests, 204 passed, 2 existing MenuIntegrationTest failures, 0 errors. |
| `git diff --check` | PASS; only Git line-ending notices. |

Existing failing assertions, also observed before QR implementation:

- `MenuIntegrationTest.createUsesStrategyAndUpdatePreservesLifecycleAndInternalDefaults`: expects internal menu response to omit costPrice; the existing implementation now returns it.
- `MenuIntegrationTest.validationAndReadOnlyFieldsCannotBypassBusinessRules`: expects costPrice input to be rejected (400); the existing implementation now accepts it (201).
- `MenuPolicyTest.java:18`: constructor invocation has not been updated for costPrice. The initial baseline ran cached classes and reported 4 runtime compilation errors; a fresh standard Maven test correctly fails at compilation.

These unrelated source/tests were not modified. Therefore the full project suite is **not** reported as passing.

For diagnostic verification only, a separate POM was created at:

```text
C:\Users\Dell\OneDrive\Documents\New project\.public-qr-verification\pom.xml
```

It points at the real repo source/resources, uses a separate `target/public-qr-verification` output directory, sets Surefire's working directory to the repo, and explicitly excludes only `**/menu/MenuPolicyTest.java` from test compilation. The repository's pom.xml is unchanged. Example focused command (from repo, Java 21 configured):

```powershell
mvn -f 'C:\Users\Dell\OneDrive\Documents\New project\.public-qr-verification\pom.xml' '-Dtest=PublicQrMenu*Test' test
```

Run logs under the repository's ignored target directory:

```text
target/public-qr-compile.log
target/public-qr-baseline.log
target/public-qr-full-standard.log
target/public-qr-focused.log
target/public-qr-regression.log
```

New integration tests create and drop their own random `it_public_qr_*` schemas. Baseline/regression runs used separate named schemas, which were dropped after verification; no dev/public schema was cleaned. No audit trigger was disabled by the new tests. Test schema removal is permanent but contains only reproducible test data.

## Remaining boundaries

No frontend, QR image generation, ordering, payment, session authorization or distributed rate limiter was added. Mask QR URLs in proxy/CDN/APM logs and keep request/JDBC parameter tracing disabled. A rotated token rejects subsequent requests but cannot erase content already downloaded. Fix the existing costPrice tests separately before expecting a green standard Maven suite.
