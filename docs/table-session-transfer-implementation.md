# TABLE transfer: implementation and verification

Implemented on 2026-10-11 in D:/Đồ án tốt nghiệp/Code/pos-saas-be.

## Result

POST /api/v1/table-sessions/{sessionId}/transfer moves the same OPEN session to an
eligible empty table. GET /api/v1/table-sessions/{sessionId}/transfers reads its
immutable, paginated movement history. Session tableId/updatedAt/@Version change;
all order/item fields, versions, IDs, snapshots and histories stay unchanged.

Controller delegates to a dedicated transactional transfer service. Eligibility,
fingerprinting/replay and history queries are separate components in TABLE.
Existing TableAccessService restaurant locking, lifecycle policies, mapper,
TableAudit/AuditService and Clock are reused. ORDER dependencies and create/append
CoR/Strategy/fingerprints were not modified.

V20 adds history that also serves as the successful-operation ledger, composite
tenant foreign keys, unique tenant/key and session/applied-version constraints,
a history index and UPDATE/DELETE prevention trigger. New TABLE_TRANSFER defaults
to OWNER/MANAGER/WAITER. Existing overrides and package/subscription data remain
unchanged, including DENY precedence. V1–V19 are byte-for-byte unchanged.

Headers, bodies, responses, permissions and errors:
[API/Postman](table-session-transfer-api.md).
Frontend steps:
[FE synchronization](table-session-transfer-fe-sync.md).

## Files changed

- `src/main/java/com/possaas/modules/table/controller/TableSessionController.java`
- `src/main/java/com/possaas/modules/table/dto/TableRequests.java`
- `src/main/java/com/possaas/modules/table/dto/TableSessionTransferResponse.java`
- `src/main/java/com/possaas/modules/table/entity/TableSession.java`
- `src/main/java/com/possaas/modules/table/entity/TableSessionTransfer.java`
- `src/main/java/com/possaas/modules/table/repository/TableSessionTransferRepository.java`
- `src/main/java/com/possaas/modules/table/service/TableConflictTranslator.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionTransferIdempotency.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionTransferPolicy.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionTransferQueryService.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionTransferService.java`
- `src/main/resources/db/migration/V20__add_table_session_transfer.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/table/TableOpenApiTest.java`
- `src/test/java/com/possaas/modules/table/TableSessionTransferIntegrationTest.java`
- `src/test/java/com/possaas/modules/table/TableSessionTransferPolicyTest.java`
- `docs/order-api.md`
- `docs/table-session-transfer-api.md`
- `docs/table-session-transfer-fe-sync.md`
- `docs/tenant-api.md`
- `docs/openapi/tenant-api.yaml`
- `README.md`
- `docs/table-session-transfer-implementation.md`

## Verification and baseline limitations

Toolchain available locally: Maven 3.9.9 and JDK 24, compiling release 21.
No Java 21 runtime verification was performed.

Original baseline `mvn test` stops at test compilation:
`src/test/java/com/possaas/modules/menu/MenuPolicyTest.java:18` passes the old
ItemCreationContext constructor arguments, missing costPrice. This unrelated
file was not changed in the delivered repository.

After promotion, `mvn compile` in the actual repository succeeded; `mvn test`
was run there and stopped at that same MenuPolicyTest constructor compilation
error before executing tests. All 23 promoted source/document/test files were
verified against the diagnostic copy by SHA-256.

In the isolated diagnostic copy only, that fixture received BigDecimal.ZERO for
costPrice so that the entire suite could execute. No POM test exclusions or
disabled tests were used. Final diagnostic full suite result:

- 412 tests executed, 410 passed, 2 failures, 0 errors, 0 skipped.
- 25 TableSessionTransferIntegrationTest tests passed.
- 4 TableSessionTransferPolicyTest tests passed.
- Existing TABLE, ORDER (100 integration cases), auth, staff, public QR/menu,
  subscription and migration suites passed except the two existing MENU assertions.
- Flyway clean-to-V20 and V19-to-V20 preservation tests passed.

The two failures are existing costPrice contract expectations in
MenuIntegrationTest:
- createUsesStrategyAndUpdatePreservesLifecycleAndInternalDefaults, line 116,
  still expects costPrice absent from tenant response.
- validationAndReadOnlyFieldsCannotBypassBusinessRules, line 198,
  still treats costPrice as a forbidden request field.

Both failures were reproduced against a separate unmodified baseline copy with
only the same constructor fixture correction. Neither MENU production code nor
these assertions were changed by this task.

A first full-suite run exhausted PostgreSQL connections in the STAFF context.
Re-running with test-only environment settings maximum-pool-size=4 and
minimum-idle=0 resolved those errors. Production configuration is unchanged.

Tests used UUID-named isolated schemas. No migration was applied to public/dev,
and target_old was not edited.
The three full-suite base schemas created by this task were removed afterward;
the dynamic TABLE/ORDER/QR/menu/migration schemas were cleaned by their tests.

## Coverage of the requested scenarios

Combined test methods cover multiple scenarios; numbering refers to the request.

| Scenarios | Evidence |
| --- | --- |
| 1–8 | emptySessionMovesWithoutNewSessionOrConfigurationChanges; confirmedOrderAndSnapshotsStayUnchangedThenAppendAndBothReplaysWork |
| 9–10, 25–26 | confirmedOrderAndSnapshotsStayUnchangedThenAppendAndBothReplaysWork |
| 11, 19, 21–22, 39–41 | journeyHistoryUsesSnapshotsAndAppliedVersionWithFixedClock; inputValidationAndEmptyHistoryContract |
| 12–17 | freshTransferRejectsLifecycleSameTargetAndStaleVersionWithoutHistory; targetEligibilityAndForeignResourcesAreChecked |
| 18, 45 | transferOnlyNeedsTransferFeatureAndPermissionAndDoesNotLimitCapacity |
| 20 | fingerprintDetectsAllChangesAndBlankReasonNormalizes; fingerprintOnlyDependsOnFrozenIntentAndUsesNormalizedReason |
| 23, 42–44, 48 | missingPermissionFeatureAndCurrentAuthAreCheckedEvenForReplay; currentBearerAccountAndRestaurantGuardsApplyBeforeReplay; systemScopeCannotTransfer |
| 24, 33–35 | auditAndHistoryFailureRollbackAndLeaveKeyAvailable; realUniqueHistoryConflictRollsBackMovementAndHasStableError |
| 27–28 | twoSessionsCompeteForTargetAndSameSessionCompetesWithStaleVersion |
| 29 | transferRacesWithManualOpen |
| 30, 32 | transferRacesWithTableCreateAndAppendWithValidSerialResults |
| 31 | transferRacesWithSessionCancelAndUpdate |
| 36–38 | postgresEnforcesAppendOnlyKeysVersionsAndTenantForeignKeys; compositeFksRejectExistingForeignTenantIdentifiers |
| 46–47 | migrationGrantsAndExplicitDenyAreRespected; staffCatalogAndAssignmentSupportTransferWithoutHardcoding |
| 49–50 | FlywayCleanMigrationIntegrationTest clean schema and v20PreservesV19SeatingOrdersQrAndPermissionOverrides |
| 51 | TableOpenApiTest operation count, transfer schema/headers and all local references |
| 52 | Full suite, subject to baseline MENU limitations stated above |
| Additional | simultaneousRetryAppliesOnlyOnce; keysAreTenantScopedAndAuditOmitsReplayAndQrSecrets; historyUsesBoundedQueriesIndependentOfPageRowCount; inconsistentSourceFailsClosedButOccupiedInactiveSourceCanMove |

## Deployment and remaining scope

Apply V20 through the normal Flyway deployment process, then refresh/login to
issue JWTs with the new default permission. Follow the FE synchronization guide
to retain frozen retry intent and keep the existing order detail screen.

No Flutter/Web implementation, merge/split/swap, payment, invoice, refund,
KDS, QR ordering or session-close endpoint was added. The full Maven test command
in the actual repository still needs the unrelated MENU fixture/assertions
brought into line with the existing costPrice contract.

