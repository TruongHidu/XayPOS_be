# QR order approval — implementation and verification

Implemented backend only for QR TABLE and QR RESTAURANT/takeaway. API examples,
headers, bodies, responses and Postman scenarios: [public-qr-order-api.md](public-qr-order-api.md).
Frontend synchronization: [qr-order-fe-sync.md](qr-order-fe-sync.md).
Schema: [public-qr-order-api.yaml](openapi/public-qr-order-api.yaml).

## Business rules

- Guest submission creates only a PENDING request and quoted request lines: no order,
  seating, kitchen history, bill or occupancy change. Neither OPEN session nor PIN is required.
- Server fixes TABLE → DINE_IN/QR_TABLE and RESTAURANT → TAKEAWAY/QR_STATIC.
  Grant identity is separate from staff users and maxStaff. Only token SHA-256 digests are stored.
- Staff approval is atomic under the shared restaurant pessimistic lock. TABLE may open
  seating using the real reviewer, create an order, or append to the reviewed existing serving
  order. RESTAURANT always creates an independent takeaway order; no TABLE/POS_QUICK_ORDER gate.
- Approval requires ORDER_CREATE + ORDER_UPDATE; TABLE_OPEN only when actually opening seating.
  Read requires ORDER_VIEW, reject ORDER_UPDATE, with the existing tenant/operational guards.
- Reviewed price/seating/order version must match at approval. Estimates remain immutable;
  approvedAmount is the amount of the request's own newly approved lines, not the whole bill.
- Submission replay binds normalized payload, guest identity and immutable scope. Approval
  replay returns the original linkage even after payment/completion/closure, without mutation.
  Reject replay preserves the original reviewer/reason and does not add audit records.
- Checkout cancels only PENDING TABLE requests that observed that exact current session/scope.
  Payment rollback rolls back cancellation. Close/cancel/transfer/table QR rotation invalidates
  the affected table epochs/grants/requests. Restaurant token rotation affects only its old
  RESTAURANT credential. Approved order history is not deleted or changed by invalidation.
- Guests see only their own receipt/linked lines. Queue filtering happens before pagination;
  request items are batch-loaded. Expiry is effective without a scheduled job.

## Architecture

The new `modules/qrorder` owns grants, request entities/repositories/DTOs, scope/quote
policies, guest submission and staff review/approval. Controllers contain no business rules.
The submission and approval pipelines reuse the immutable ORDER CoR composition.
`OrderQrApprovalOperation` reuses the existing DINE_IN/TAKEAWAY Strategy, line factory,
money, confirmation, aggregate status, history and audit operations. Private staff create/append
normalizers, DTOs and idempotency fingerprints are unchanged.

Lifecycle interfaces belong to their consumers (TABLE, RESTAURANT, PAYMENT); QR implements
them. ORDER owns `PaymentCheckoutQuery`, implemented by PAYMENT. This avoids introducing
reverse module imports or constructor cycles. All mutable operations join one transaction.
The read-only review observation suspends a read transaction when catching business denials,
so a unavailable feature/menu becomes `canApprove=false`, not rollback-only/HTTP 500.

The request JSON converter is limited to the four QR request DTO types, ensuring their scoped
Jackson 2 deserializers run with Boot 4 MVC. It does not change the global mapper or staff API
serialization/fingerprint bytes. QR POST bodies are bounded to 32 KiB, including chunked input.

## Migration

`V25__create_qr_order_requests.sql` adds `qr_table_access`, `qr_guest_authorizations`,
`qr_order_requests`, `qr_order_request_items`, tenant foreign keys, scope/outcome checks and
queue/receipt/idempotency indexes. Existing tables are seeded with access epoch 0.
V1–V24 and the V19 single-serving-order index remain unchanged. No package/feature mapping,
staff limit, permission seed or ACTIVE feature snapshot is altered.

Migration was verified in disposable PostgreSQL schemas, including empty-database migration
and an upgrade from V24 preserving packages, subscriptions, roles, existing orders and tables.
This task does not run V25 against the application's normal database. It will run through
the existing Flyway startup/deployment workflow when the backend is started.

## Verification

Environment: Java 21, Maven 3.9.9, PostgreSQL 17.11; isolated test schemas only.

- Original `mvn clean test`: production compilation succeeds; test compilation is blocked
  by the pre-existing `MenuPolicyTest.java:18` constructor call missing the `costPrice` argument.
- Local diagnostic POM excludes only that test compilation error, without modifying the
  repository POM or MENU source/tests: 687 tests executed, 685 passed, two existing MENU failures.
- All 117 newly added tests pass: 57 HTTP integration scenarios, guest/scope/rate/body/lifecycle
  policies, approval/domain tests, DTO/OpenAPI contracts and V24 → V25 migration.
- ORDER/PAYMENT/TABLE/Flyway regression tests pass, including atomic completion/session closure,
  direct bulk served, legacy hash fixtures, table transfer, payment rollback and partial indexes.
- The TABLE test cleanup now removes its QR child records before parent rows, following the
  new V25 foreign keys. This is scoped fixture maintenance, not a change to production deletion.
- Original-POM production JAR build with `mvn -Dmaven.test.skip=true package`: BUILD SUCCESS.
  Tests were verified separately as described above; this does not claim the original test suite is green.

The two unchanged MENU assertions still expect `costPrice` to be hidden/read-only, contrary
to the previous MENU API change. They were deliberately not rewritten as part of QR work.

## Changed file groups

- New `src/main/java/com/possaas/modules/qrorder/**`: model, persistence, APIs, policies,
  CoR handlers, JSON configuration and lifecycle implementation.
- ORDER: new trusted approval/checkout ports and operations; payment port exposes the order's
  session ID for scoped checkout cancellation. Existing staff command contracts are preserved.
- TABLE: new pure seating observation and lifecycle consumer ports; close/cancel/transfer,
  QR rotation/disable/delete writers invoke scoped invalidation.
- PAYMENT: checkout query adapter/consumer port and synchronous cancellation hook in settlement.
- RESTAURANT: menu credential lifecycle port and rotation hook.
- Infrastructure: exact guest route allowlist, bearer/status-filter bypass for those routes only,
  no-store/body-budget filters; CORS exposes Retry-After. Audit redaction covers guest/key/hash fields.
- Resources: QR TTL/rate configuration and V25. Tests: QR/domain/migration additions,
  clean-migration version/table assertions and TABLE fixture FK cleanup.
- Docs: new API/OpenAPI/FE/implementation guides; README and existing ORDER/TABLE/PAYMENT/public-menu
  documents updated without changing their endpoint contracts.

An exact per-file promotion manifest and pre-change backups are retained in the local verification
workspace; only hash-verified changed source/docs files are applied to the actual repository.
No `target_old`, Flutter/web implementation, production data or unrelated dirty changes are modified.

## Remaining limits

No frontend implementation, WebSocket/outbox, guest editing/cancellation, direct guest order,
gateway/webhook, refund/invoice/printing, recipe/inventory or delivery/CRM is included.
Rate limiting is bounded but single-instance; scale-out needs shared/edge limiting. Tokens,
epoch, TTL and rate limits are not proof of physical presence: staff must verify the guest party.
There is no cleanup job; request/grant rows and history are retained. Ordering features must
already exist in the effective immutable subscription snapshot; standard packages are not auto-granted.

Current PAYMENT MVP has only manual SUCCESS settlement. The lifecycle callback is used there,
and a future PENDING payment-intent writer must invoke the same consumer-owned callback inside
its successful creation transaction under the restaurant lock. Pending checkout reads are already
supported; this task does not add such a provider/intent endpoint.

## Exact changed files

The following 103 source/test/docs files are the scoped promotion set:

- `docs/openapi/order-api.yaml`
- `docs/openapi/public-qr-order-api.yaml`
- `docs/order-api.md`
- `docs/payment-api.md`
- `docs/public-qr-menu-api.md`
- `docs/public-qr-order-api.md`
- `docs/public-restaurant-menu-api.md`
- `docs/qr-order-fe-sync.md`
- `docs/qr-order-implementation.md`
- `docs/table-session-transfer-api.md`
- `docs/tenant-api.md`
- `README.md`
- `src/main/java/com/possaas/config/CorsConfig.java`
- `src/main/java/com/possaas/config/SecurityConfig.java`
- `src/main/java/com/possaas/infrastructure/security/JwtAuthenticationFilter.java`
- `src/main/java/com/possaas/infrastructure/security/PublicQrOrderRequests.java`
- `src/main/java/com/possaas/infrastructure/security/QrOrderBodyLimitFilter.java`
- `src/main/java/com/possaas/infrastructure/security/QrOrderNoStoreFilter.java`
- `src/main/java/com/possaas/infrastructure/security/TenantRestaurantStatusFilter.java`
- `src/main/java/com/possaas/infrastructure/security/UserAccountStatusFilter.java`
- `src/main/java/com/possaas/modules/audit/service/AuditDataRedactor.java`
- `src/main/java/com/possaas/modules/order/application/port/OrderPaymentCommand.java`
- `src/main/java/com/possaas/modules/order/application/port/PaymentCheckoutQuery.java`
- `src/main/java/com/possaas/modules/order/application/port/QrApprovedOrderCommand.java`
- `src/main/java/com/possaas/modules/order/application/port/QrOrderCheckoutQuery.java`
- `src/main/java/com/possaas/modules/order/service/OrderPaymentCommandAdapter.java`
- `src/main/java/com/possaas/modules/order/service/OrderQrApprovalOperation.java`
- `src/main/java/com/possaas/modules/order/service/QrOrderCheckoutQueryAdapter.java`
- `src/main/java/com/possaas/modules/payment/application/port/PaymentGuestRequestLifecycle.java`
- `src/main/java/com/possaas/modules/payment/repository/PaymentRepository.java`
- `src/main/java/com/possaas/modules/payment/service/PaymentCheckoutQueryAdapter.java`
- `src/main/java/com/possaas/modules/payment/service/PaymentSettlementService.java`
- `src/main/java/com/possaas/modules/qrorder/application/port/QrRequestLifecycle.java`
- `src/main/java/com/possaas/modules/qrorder/config/QrOrderRequestJsonConfiguration.java`
- `src/main/java/com/possaas/modules/qrorder/controller/PublicQrOrderController.java`
- `src/main/java/com/possaas/modules/qrorder/controller/PublicQrOrderErrorHandler.java`
- `src/main/java/com/possaas/modules/qrorder/controller/QrStaffErrorHandler.java`
- `src/main/java/com/possaas/modules/qrorder/controller/QrStaffRequestController.java`
- `src/main/java/com/possaas/modules/qrorder/dto/QrApprovalResponse.java`
- `src/main/java/com/possaas/modules/qrorder/dto/QrGuestAccessResponse.java`
- `src/main/java/com/possaas/modules/qrorder/dto/QrGuestRequests.java`
- `src/main/java/com/possaas/modules/qrorder/dto/QrPublicResponse.java`
- `src/main/java/com/possaas/modules/qrorder/dto/QrStaffRequestDetail.java`
- `src/main/java/com/possaas/modules/qrorder/dto/QrStaffRequests.java`
- `src/main/java/com/possaas/modules/qrorder/dto/QrStaffRequestSummary.java`
- `src/main/java/com/possaas/modules/qrorder/dto/QrStaffSearch.java`
- `src/main/java/com/possaas/modules/qrorder/entity/QrEntryType.java`
- `src/main/java/com/possaas/modules/qrorder/entity/QrGuestAuthorization.java`
- `src/main/java/com/possaas/modules/qrorder/entity/QrOrderRequest.java`
- `src/main/java/com/possaas/modules/qrorder/entity/QrOrderRequestItem.java`
- `src/main/java/com/possaas/modules/qrorder/entity/QrRecord.java`
- `src/main/java/com/possaas/modules/qrorder/entity/QrRequestStatus.java`
- `src/main/java/com/possaas/modules/qrorder/entity/QrTableAccess.java`
- `src/main/java/com/possaas/modules/qrorder/repository/QrGuestAuthorizationRepository.java`
- `src/main/java/com/possaas/modules/qrorder/repository/QrOrderRequestItemRepository.java`
- `src/main/java/com/possaas/modules/qrorder/repository/QrOrderRequestRepository.java`
- `src/main/java/com/possaas/modules/qrorder/repository/QrStaffRequestSpecifications.java`
- `src/main/java/com/possaas/modules/qrorder/repository/QrTableAccessRepository.java`
- `src/main/java/com/possaas/modules/qrorder/service/chain/QrApprovalContext.java`
- `src/main/java/com/possaas/modules/qrorder/service/chain/QrApprovalPersistHandler.java`
- `src/main/java/com/possaas/modules/qrorder/service/chain/QrApprovalPrepareHandler.java`
- `src/main/java/com/possaas/modules/qrorder/service/chain/QrApprovalReplayHandler.java`
- `src/main/java/com/possaas/modules/qrorder/service/chain/QrApprovalReviewHandler.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrApprovalTargetPolicy.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrGuestAccessService.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrGuestSubmissionService.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrHashes.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrLifecycleService.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrQuoteService.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrRateLimiter.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrRateLimitException.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrRequestAudit.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrRequestMapper.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrReviewContextService.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrScopePolicy.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrStaffAccessService.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrStaffDecisionService.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrStaffQueryService.java`
- `src/main/java/com/possaas/modules/qrorder/service/QrStaffResponseMapper.java`
- `src/main/java/com/possaas/modules/restaurant/application/port/RestaurantMenuGuestAccessLifecycle.java`
- `src/main/java/com/possaas/modules/restaurant/service/RestaurantMenuLinkCommandService.java`
- `src/main/java/com/possaas/modules/table/application/port/QrOrderSeatingQuery.java`
- `src/main/java/com/possaas/modules/table/application/port/TableGuestAccessLifecycle.java`
- `src/main/java/com/possaas/modules/table/service/QrOrderSeatingQueryAdapter.java`
- `src/main/java/com/possaas/modules/table/service/RestaurantTableCommandService.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionClosureOperation.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionCommandService.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionTransferService.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V25__create_qr_order_requests.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/migration/QrOrderMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderQrApprovalOperationTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrGuestPolicyTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrLifecycleServiceTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrOrderBodyLimitFilterTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrOrderIntegrationTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrOrderOpenApiTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrRateLimiterTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrRequestDtoContractTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrScopePolicyTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrStaffApprovalPolicyTest.java`
- `src/test/java/com/possaas/modules/table/TableIntegrationTest.java`
