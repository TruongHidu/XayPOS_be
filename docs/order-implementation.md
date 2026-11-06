# ORDER implementation report

Latest delivery: [V19 single serving order + three command CoR](order-cor-implementation.md).
Các mục SUBMIT và số liệu bên dưới là lịch sử các lượt trước; serving-cardinality
đã được V19 thay đổi. Không sửa migration cũ dù comment V18 cho phép nhiều order.

## SUBMIT / gọi thêm (2026-10-08)

Luồng tham khảo KiotTay: nhân viên gửi một bước, gọi thêm vào orderId cụ thể, server snapshot giá và mỗi lần gọi thêm tạo dòng độc lập. POS SaaS không sao chép float, count+1, HTTP request context, xóa vật lý hoặc core-state qua after-commit listener. Vẫn dùng TableSession, BigDecimal, Clock, tenant restaurant lock và aggregate @Version.

- `OrderSubmissionMode`, Create/AddItems optional field: absent/null DRAFT, SUBMIT explicit.
- `OrderSubmissionPolicy`: quyền create DRAFT=ORDER_CREATE, SUBMIT=CREATE+UPDATE; append=UPDATE, kiểm trước replay.
- `OrderAppendPolicy`: DRAFT OPEN-only, SUBMIT OPEN/CONFIRMED, unpaid/currency/seating checks riêng; không thay requireEditable của metadata/old lines.
- `OrderConfirmationOperation`: core confirmation đồng bộ, MANDATORY join transaction, recheck mọi dòng live; creation/append/legacy confirm dùng chung. CONFIRMED append không gọi no-op confirm giả và không ghi audit confirmation trùng.
- `OrderAppendService`: orchestration riêng, dùng ports/factory/calculator/history/audit hiện có. OPEN append+confirm có thể tăng version hơn một lần trong cùng transaction; luôn dùng version response, không tự tính version phía FE.
- `OrderAppendIdempotency`, `OrderItemSubmission`, repository và V18: successful append ledger cùng transaction; tenant+key unique, composite FK tenant/order. Create key/hash trên root không đổi.
- `OrderIdempotencyService`: eight-field legacy JSON projection giữ hash DRAFT, SUBMIT có namespace/version fingerprint riêng. Không backfill hash lịch sử.
- Tất cả dòng mới PENDING và kitchen timestamp null. Một session vẫn nhiều order; chỉ append target được chọn.

File sửa: OrderRequests, OrderRequestNormalizer, OrderIdempotencyService, OrderCreationService, OrderCommandService, OrderCommandController, OrderConflictHandler; OrderPolicyTest/OrderIntegrationTest/OrderOpenApiTest/FlywayCleanMigrationIntegrationTest; README/order-api/OpenAPI và report này. File thêm: OrderSubmissionMode, OrderSubmissionPolicy, OrderAppendPolicy, OrderConfirmationOperation, OrderAppendService, OrderAppendIdempotency, OrderItemSubmission, OrderItemSubmissionRepository, V18 và [order-submit-fe-sync.md](order-submit-fe-sync.md). Không sửa app/web, V1–V17 hoặc target_old.

Kết quả kiểm thử cập nhật được ghi ở mục cuối báo cáo này. Các số liệu phía dưới là lịch sử trước khi bổ sung SUBMIT, không phải kết quả lượt hiện tại.

Verified on 2026-10-08 with Java 21 and PostgreSQL 17.

API/Postman: [order-api.md](order-api.md). OpenAPI: [order-api.yaml](openapi/order-api.yaml).

## Delivered behavior

- Nine authenticated endpoints for list/detail/create, draft metadata, adding/updating/cancelling lines, confirmation and order cancellation.
- DINE_IN/TAKEAWAY Strategy registry/factory. Strategy resolves serving context; shared pipeline handles menu snapshots, money, persistence, idempotency and audit.
- Authoritative name/unit/price/currency snapshots, HALF_UP scale-2 line totals, cancelled lines excluded without erasing their snapshots.
- Order @Version with an internal mutationSequence dirty scalar, so child-only changes advance aggregate version under Clock.fixed and zero totals. DTO version matches committed reload; no-op/replay does not advance it.
- CREATE Idempotency-Key + normalized actor/payload SHA-256 + tenant unique constraint. New=201, resource replay=200/current representation; replay precedes current session/menu checks after auth/features/tenant lock.
- Real TABLE usage port implemented by ORDER repository. Same restaurant write lock serializes creation/menu mutations/session cancellation. Only fully cancelled, UNPAID, zero-paid orders permit seating cancellation.
- History append-only trigger and monotonic changeSequence; audit/history/order mutations share a transaction. No successful audit for rejected requests/no-op/replay.
- CORS origins unchanged; Idempotency-Replayed exposed for web clients. Private JWT/account/restaurant/feature guards retained; no public order write.

## Migrations

- V16__create_order_schema.sql: orders, order_items, order_item_status_history; tenant composite FKs, code/key uniqueness, monetary/quantity/context constraints, indexes, history append-only trigger. No cascade history deletion. lineNumber gives stable display order; mutationSequence/changeSequence are internal bookkeeping.
- V17__grant_order_staff_permissions.sql: minimal CASHIER/MANAGER role grants. Existing user GRANT/DENY and subscription snapshots are untouched. Refresh/login required for updated JWT permission claims.
- V1–V15 and target_old were not edited. No dev/public schema was cleaned or manually migrated by the implementation; normal application startup applies the new Flyway files when the user runs the backend.

## Created source files

Under src/main/java/com/possaas/modules/order:

```text
controller/OrderQueryController.java
controller/OrderCommandController.java
controller/OrderConflictHandler.java
dto/OrderRequests.java
dto/OrderResponse.java
dto/OrderSummaryResponse.java
dto/OrderItemResponse.java
dto/OrderSearch.java
entity/OrderRecord.java
entity/Order.java
entity/OrderItem.java
entity/OrderItemStatusHistory.java
entity/ServiceType.java
entity/SourceChannel.java
entity/OrderStatus.java
entity/OrderItemStatus.java
entity/OrderPaymentStatus.java
repository/OrderRepository.java
repository/OrderItemRepository.java
repository/OrderItemStatusHistoryRepository.java
repository/OrderSpecifications.java
service/OrderAccessService.java
service/OrderAudit.java
service/OrderCodeGenerator.java
service/OrderCommandService.java
service/OrderCreationService.java
service/OrderFactory.java
service/OrderHistory.java
service/OrderIdempotencyService.java
service/OrderLifecyclePolicy.java
service/OrderLineFactory.java
service/OrderMoneyCalculator.java
service/OrderQueryService.java
service/OrderRequestNormalizer.java
service/OrderResponseMapper.java
service/OrderSessionUsageAdapter.java
service/strategy/OrderCreationContext.java
service/strategy/OrderCreationPlan.java
service/strategy/OrderCreationStrategy.java
service/strategy/OrderCreationStrategyRegistry.java
service/strategy/DineInOrderCreationStrategy.java
service/strategy/TakeawayOrderCreationStrategy.java
```

Other source additions:

```text
modules/menu/application/port/OrderMenuQuery.java
modules/menu/application/port/SellableMenuItem.java
modules/menu/service/OrderMenuQueryAdapter.java
modules/table/application/port/OrderSeatingQuery.java
modules/table/application/port/TableSessionUsagePolicy.java
modules/table/service/OrderSeatingQueryAdapter.java
```

Other additions: V16/V17, OrderPolicyTest, OrderIntegrationTest, OrderOpenApiTest, docs/order-api.md, docs/openapi/order-api.yaml and this report.

## Existing files updated in this task

```text
src/main/java/com/possaas/config/CorsConfig.java
src/main/java/com/possaas/modules/menu/repository/ItemRepository.java
src/main/java/com/possaas/modules/table/service/TableSessionCommandService.java
src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java
README.md
docs/tenant-api.md
```

The worktree already contained uncommitted public-menu/URL work before this task; it was preserved. No frontend files were edited for ORDER.

## Verification results and baseline blockers

| Check | Result |
| --- | --- |
| Main source compilation | PASS: 341 source files |
| New ORDER tests | PASS: 50 (40 PostgreSQL integration, 8 policy, 2 OpenAPI) |
| TABLE integration regression | PASS: 22 |
| Flyway clean/upgrade tests | PASS: 5, latest version 17 and 20 business tables |
| Diagnostic full regression | 293 tests: 291 passed, 2 existing MenuIntegrationTest failures, 0 errors |
| Standard Maven test | Blocked at MenuPolicyTest.java:18 missing new costPrice constructor argument |

Baseline before ORDER: 249 tests, 2 failures in MenuIntegrationTest and 4 runtime compilation errors in cached MenuPolicyTest classes. After fresh compilation, standard Maven correctly reports the MenuPolicyTest constructor mismatch during test compilation.

Existing MenuIntegrationTest assertions still expect costPrice to be absent from the internal response and rejected on input. The internal API already supports costPrice. These tests and their implementation were not modified. The standard/full project suite is not reported as green.

Diagnostic POM outside the repository:

```text
C:\Users\Dell\OneDrive\Documents\New project\.order-verification\pom.xml
```

It references actual repo source/resources, outputs to target/order-verification and explicitly excludes only **/menu/MenuPolicyTest.java from test compilation. Repository pom.xml remains unchanged. This diagnostic exclusion is not a fix for the baseline test.

Example verification, from the repository with Java 21 configured:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/kiot_tay_db?currentSchema=it_order_regression_20261008'
$env:SPRING_FLYWAY_SCHEMAS = 'it_order_regression_20261008'
$env:SPRING_FLYWAY_DEFAULT_SCHEMA = 'it_order_regression_20261008'
$env:SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA = 'it_order_regression_20261008'
$env:SUBSCRIPTION_EXPIRATION_ENABLED = 'false'
$env:SUPER_ADMIN_BOOTSTRAP_ENABLED = 'false'
mvn -f 'C:\Users\Dell\OneDrive\Documents\New project\.order-verification\pom.xml' test
```

Logs under ignored target: order-baseline.log, order-compile.log, order-focused.log, order-standard.log and order-regression.log. New integration tests create/drop only random it_order_* schemas and do not disable audit/history triggers. Named baseline/focused/regression verification schemas are removed after completion; their data is reproducible test data.

## Boundaries and extension points

ORDER currently supports staff only. QR menu tokens do not grant create/update/confirm/payment/session authority. Future guest endpoints require explicit guest authorization/context and ordering feature rules before reusing the creation core; no fake staff account is introduced.

There are no KDS/payment/complete/close/inventory/recipe endpoints in this delivery. Source/status enums reserve those states, but no API sets them freely. Submitted additions now use the explicit existing orderId; legacy draft additions and old-data edits remain OPEN-only. History/snapshot IDs and the separated serving strategies support later recipe/versioned stock integration; stock is not simulated here.

## Verification — current SUBMIT delivery

| Check | Current result |
| --- | --- |
| Main compilation / JPA schema validation | PASS with Java 21, PostgreSQL 17 |
| ORDER | PASS: 79 tests (65 integration, 11 policy, 3 OpenAPI); 29 additional tests |
| TABLE regression | PASS: 22 integration tests |
| Flyway clean/upgrade | PASS: 5, latest V18, 21 business tables |
| Focused staging build | PASS: 106 tests, no failures/errors/skips |
| Full diagnostic build of actual repository | 322 tests: 320 passed, 2 baseline MenuIntegrationTest failures, 0 errors/skips |
| Standard `mvnw.cmd test -q` | BLOCKED: MenuPolicyTest.java:18 missing costPrice argument in ItemCreationContext constructor |
| Standard `mvnw.cmd clean test -q` | BLOCKED before compilation: locked target/classes/application-dev.yml; no user process was stopped |

Baseline before these changes: standard incremental build ran cached classes, 299 tests with the same 2 MENU assertion failures and 4 MENU runtime compilation errors. Fresh compilation identifies the constructor mismatch instead. MENU code/tests were deliberately not changed.

Full diagnostic command uses the existing external `.order-verification/pom.xml`, which excludes only `**/menu/MenuPolicyTest.java` from test compilation; it is not a workaround committed to pom.xml or a claim that the standard suite passes. Full actual-source run:

```powershell
$env:JAVA_HOME = 'D:\jdk java'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:DB_URL = 'jdbc:postgresql://localhost:5432/kiot_tay_db?currentSchema=it_submit_regression_20261008'
$env:SPRING_FLYWAY_SCHEMAS = 'it_submit_regression_20261008'
$env:SPRING_FLYWAY_DEFAULT_SCHEMA = 'it_submit_regression_20261008'
$env:SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA = 'it_submit_regression_20261008'
$env:SUBSCRIPTION_EXPIRATION_ENABLED = 'false'
$env:SUPER_ADMIN_BOOTSTRAP_ENABLED = 'false'
mvn -f 'C:\Users\Dell\OneDrive\Documents\New project\.order-verification\pom.xml' clean test
```

Logs retained outside the repository at `C:\Users\Dell\OneDrive\Documents\New project\order-submit-be-work`: focused-test.log, standard-test.log, standard-retry.log, regression-test.log. Actual-source XML reports: target/order-verification/surefire-reports. No V1–V17 migration changed and no dev/public schema was cleaned/migrated in this task. The three named test schemas created by this task (it_submit_baseline_20261008, it_submit_focus_20261008, it_submit_regression_20261008) were removed after verification using an explicit whitelist and Flyway-version check; only reproducible test data was removed. Random integration schemas were cleaned by their own guarded test teardown.

Exact 25-file manifest for this delivery (not the unrelated pre-existing dirty worktree):

```text
src/main/java/com/possaas/modules/order/dto/OrderRequests.java
src/main/java/com/possaas/modules/order/service/OrderRequestNormalizer.java
src/main/java/com/possaas/modules/order/service/OrderIdempotencyService.java
src/main/java/com/possaas/modules/order/service/OrderCreationService.java
src/main/java/com/possaas/modules/order/service/OrderCommandService.java
src/main/java/com/possaas/modules/order/controller/OrderCommandController.java
src/main/java/com/possaas/modules/order/controller/OrderConflictHandler.java
src/main/java/com/possaas/modules/order/entity/OrderSubmissionMode.java
src/main/java/com/possaas/modules/order/entity/OrderItemSubmission.java
src/main/java/com/possaas/modules/order/repository/OrderItemSubmissionRepository.java
src/main/java/com/possaas/modules/order/service/OrderSubmissionPolicy.java
src/main/java/com/possaas/modules/order/service/OrderAppendPolicy.java
src/main/java/com/possaas/modules/order/service/OrderConfirmationOperation.java
src/main/java/com/possaas/modules/order/service/OrderAppendService.java
src/main/java/com/possaas/modules/order/service/OrderAppendIdempotency.java
src/main/resources/db/migration/V18__create_order_item_submissions.sql
src/test/java/com/possaas/modules/order/OrderPolicyTest.java
src/test/java/com/possaas/modules/order/OrderIntegrationTest.java
src/test/java/com/possaas/modules/order/OrderOpenApiTest.java
src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java
docs/order-api.md
docs/openapi/order-api.yaml
docs/order-implementation.md
docs/order-submit-fe-sync.md
README.md
```

Required test coverage:

- 1–7: absent/null/explicit DRAFT, atomic SUBMIT DINE_IN/TAKEAWAY, both permissions before replay, legacy stored hash fixture, mode/key conflicts.
- 8–9: audit/history/confirmation rollback and no fake kitchen state/timestamp.
- 10–15: explicit confirmed target, OPEN append+confirm, recheck old unsellable menu rollback, immutable old snapshots/confirmedAt, new IDs/current snapshots/history and no merging.
- 16–20: currency, session CLOSED/CANCELLED, table deleted/inactive and area inactive, financial/terminal guards, old edits remain OPEN-only, auto-confirm cancel permission boundary.
- 21–27: matching replay no mutation/audit/version/history, target/actor/payload/version conflicts, replay after root cancelled and seating/menu changed, same/different-key races, Clock.fixed/zero price and rollback ledger.
- 28–32: foreign tenant hidden, independent tenant keys/composite FK/unique constraint, multiple orders/session and chosen target, append/session-cancel plus legacy create/cancel races, Flyway clean V18, existing API/security regressions.

Remaining scope: no public QR ordering, KDS transitions, payments, bill/session closing, stock/recipe deduction, notifications/outbox. General old-line editing stays restricted; expanding PREPARING/READY/SERVED append later requires KDS/aggregate reconciliation policy, not just relaxing an enum check.
