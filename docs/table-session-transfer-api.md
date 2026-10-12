# Chuyển phiên sang bàn trống

V20 bổ sung chuyển vị trí của **cùng phiên OPEN**, giữ nguyên tất cả order/món.
Không hủy/mở lại phiên; không tạo order mới; không gộp/tách/hoán đổi bàn.

## Quyền và điều kiện

| API | Permission | Feature hiệu lực |
| --- | --- | --- |
| POST `/api/v1/table-sessions/{sessionId}/transfer` | `TABLE_TRANSFER` | `TABLE_MANAGEMENT` |
| GET `/api/v1/table-sessions/{sessionId}/transfers` | `TABLE_VIEW` | `TABLE_MANAGEMENT` |

Tenant lấy từ JWT, không nhận từ request. Account, nhà hàng và subscription phải hợp lệ
theo guard hiện có. Không yêu cầu ORDER permission/feature cho hai API này;
không dùng TABLE_OPEN/TABLE_CLOSE thay TABLE_TRANSFER, không có OWNER/SUPER_ADMIN bypass.

Migration cấp mặc định TABLE_TRANSFER cho system roles OWNER, MANAGER, WAITER;
không tự cấp CASHIER/KITCHEN. OWNER có thể GRANT/DENY qua API staff hiện có.
DENY thắng grant của role. Catalog permission tự nhận code mới thuộc module TABLE.
Không sửa package hoặc snapshot subscription. Refresh token/login lại để nhận grants
mới trong JWT; nếu refresh đã bị revoke sau đổi quyền thì phải login lại.

Bàn đích phải cùng tenant, khác bàn hiện tại, AVAILABLE, chưa xóa mềm, khu vực
nếu có phải active/chưa xóa, không có session OPEN kể cả session chưa có order.
Không giới hạn mới theo capacity. Phiên OPEN không có order, có serving order
CONFIRMED/PREPARING/READY/SERVED hoặc có lịch sử order đều được chuyển.

Giữ nguyên session ID/code, openedAt/openedBy/guestCount/note; giữ nguyên mọi field,
version, snapshot và history của order/item. Chỉ tableId, updatedAt, version của
session thay đổi, đồng thời ghi một history và một audit TABLE_SESSION_TRANSFERRED.
QR token và configuration version của bàn vật lý không đổi. occupied được suy ra
từ OPEN session: bàn nguồn trống, bàn đích nhận cùng phiên sau commit.

## POST transfer

```http
POST {{baseUrl}}/api/v1/table-sessions/{{sessionId}}/transfer
Authorization: Bearer {{accessToken}}
Accept: application/json
Content-Type: application/json
Idempotency-Key: {{transferKey}}
```

```json
{
  "targetTableId": "22222222-2222-4222-8222-222222222222",
  "expectedVersion": 3,
  "reason": "Khách muốn chuyển sang khu vực yên tĩnh"
}
```

targetTableId UUID và expectedVersion nguyên >= 0 bắt buộc. expectedVersion là
**Session.version**, không phải Order.version hay RestaurantTable.version.
reason tùy chọn, <= 1000 ký tự, trim và blank thành null; không ghi đè session.note.
Key bắt buộc, không blank, <= 100 ký tự. Unknown body fields bị từ chối.

HTTP 200 cho cả request mới và replay:

```http
Cache-Control: no-store
Idempotency-Replayed: false
```

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "sessionCode": "SESSION-001",
  "tableId": "22222222-2222-4222-8222-222222222222",
  "tableCode": "B05",
  "tableName": "Bàn 5",
  "status": "OPEN",
  "guestCount": 2,
  "note": "Ghi chú cũ của phiên",
  "openedBy": "33333333-3333-4333-8333-333333333333",
  "openedAt": "2026-10-11T03:00:00Z",
  "closedBy": null,
  "closedAt": null,
  "cancelReason": null,
  "version": 4,
  "createdAt": "2026-10-11T03:00:00Z",
  "updatedAt": "2026-10-11T03:20:00Z"
}
```

Tất cả UUID/thời gian/code/version ở đây chỉ minh họa. Version thực tế lấy sau flush.
Response giữ contract TableSessionResponse, không nhúng OrderResponse.

## Retry và concurrency

Key có namespace riêng cho transfer và unique trong tenant. Fingerprint gồm
operation `table-session-transfer-v1`, actor ID, path session ID, targetTableId,
expectedVersion và reason đã normalize. Không hash trạng thái DB/thời gian/bàn nguồn
đang resolve. Khác bất kỳ nội dung nào với key cũ trả 409 IDEMPOTENCY_KEY_REUSED.

Đây là **resource replay**, không phát lại response cũ. A→B thành công, sau đó B→C:
retry key A→B trả cùng session đang ở C, header `Idempotency-Replayed: true`.
Replay sau CLOSED/CANCELLED hoặc sau khi B bị soft-delete vẫn trả session hiện tại,
miễn auth/feature hiện tại hợp lệ. Không tăng version/updatedAt, không ghi audit/history.
Key mới chuyển đến đúng bàn hiện tại trả SAME_TABLE_TRANSFER_NOT_ALLOWED, không coi là replay.

Service giữ shared restaurant pessimistic lock tới commit, cùng boundary với open,
create order, append và update/cancel session. Ledger lookup đi trước lifecycle/version/
occupancy checks. Session update, history và audit chung transaction; lỗi rollback
tất cả và không tiêu thụ key. Không thay create/append fingerprints, CoR hoặc Strategy.

## GET lịch sử

```http
GET {{baseUrl}}/api/v1/table-sessions/{{sessionId}}/transfers?page=0&size=20
Authorization: Bearer {{accessToken}}
Accept: application/json
```

page mặc định 0, >=0; size mặc định 20, 1–100. HTTP 200, Cache-Control: no-store.
Sort cố định sessionVersionAfter giảm dần (id làm tie-breaker), đúng cả với Clock.fixed.

```json
{
  "content": [
    {
      "id": "44444444-4444-4444-8444-444444444444",
      "sessionId": "11111111-1111-4111-8111-111111111111",
      "fromTable": {"id": "55555555-5555-4555-8555-555555555555", "code": "B01", "name": "Bàn 1"},
      "toTable": {"id": "22222222-2222-4222-8222-222222222222", "code": "B05", "name": "Bàn 5"},
      "actorUserId": "33333333-3333-4333-8333-333333333333",
      "reason": "Khách muốn chuyển sang khu vực yên tĩnh",
      "sessionVersionBefore": 3,
      "sessionVersionAfter": 4,
      "transferredAt": "2026-10-11T03:20:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Phiên chưa chuyển trả content rỗng; phiên terminal vẫn đọc được. Snapshot code/name
không đổi khi bàn đổi tên/xóa mềm. Không query actor/table từng dòng (không N+1).
Không trả key/hash/QR token/password hoặc customer PII.

Session.tableId là vị trí **hiện tại/cuối cùng**. Hành trình nằm trong history;
không suy ra order lịch sử từng được tạo tại bàn hiện tại. Không bổ sung snapshot bàn
vào ORDER schema ở phạm vi này.

## Lỗi

Dùng error envelope hiện có: code, message, fieldErrors, timestamp.

| HTTP | Code/ý nghĩa |
| --- | --- |
| 400 | VALIDATION_ERROR, INVALID_REQUEST_BODY, INVALID_IDEMPOTENCY_KEY |
| 401 | UNAUTHORIZED, ACCOUNT_INACTIVE theo guard hiện có |
| 403 | FORBIDDEN, TENANT_ACCESS_DENIED, RESTAURANT_INACTIVE, SUBSCRIPTION_NOT_ACTIVE, FEATURE_NOT_ENTITLED |
| 404 | TABLE_SESSION_NOT_FOUND, TABLE_NOT_FOUND, TABLE_AREA_NOT_FOUND; tài nguyên khác tenant được che bằng 404 |
| 409 | INVALID_TABLE_SESSION_TRANSITION: phiên không OPEN |
| 409 | CONCURRENT_TABLE_UPDATE: stale session version hoặc locking/ledger constraint conflict |
| 409 | TABLE_INACTIVE, TABLE_AREA_INACTIVE, TABLE_ALREADY_OCCUPIED |
| 409 | SAME_TABLE_TRANSFER_NOT_ALLOWED: đích là bàn hiện tại |
| 409 | IDEMPOTENCY_KEY_REUSED: key đã dùng với request/actor khác |
| 409 | INCONSISTENT_TABLE_SESSION_STATE: dữ liệu phiên nguồn không nhất quán |

Không lộ SQL/stack trace. Unique violation không tự được coi là key reuse khi chưa
xác định hash mismatch. Request bị từ chối không tạo audit thành công.

## Postman flow

1. Login/refresh để JWT có TABLE_TRANSFER; kiểm tra feature TABLE_MANAGEMENT.
2. GET `/api/v1/tables?status=AVAILABLE&occupied=false` lấy bàn đích; đây chỉ là gợi ý,
   backend vẫn kiểm tra dưới lock. Nếu cần hiển thị area, lọc area inactive ở UI.
3. GET `/api/v1/table-sessions/{sessionId}` lấy version hiện tại.
4. Gửi POST transfer với key mới (ví dụ UUID) và giữ lại nguyên intent/key.
5. Kiểm tra source current-session trả 204, target current-session trả cùng session ID.
6. GET `/api/v1/table-sessions/{sessionId}/active-order` với ORDER_VIEW và các feature
   ORDER hiện có: vẫn cùng order. Transfer permission không tự cấp quyền xem đơn.
7. Retry nguyên POST: 200, replay=true, không tăng session version/history count.
8. GET transfers: đúng một dòng. Thử key cũ với reason khác: 409 IDEMPOTENCY_KEY_REUSED.
9. Thử key mới với version cũ: 409 CONCURRENT_TABLE_UPDATE; reload context để xử lý.

## Triển khai

V20__add_table_session_transfer.sql thêm history/ledger, composite tenant FKs,
unique key/version, append-only UPDATE/DELETE trigger và permission/default grants.
Giữ V1–V19 và các index ux_table_session_open, ux_order_serving_session nguyên trạng.
Không backfill lịch sử giả cho các phiên cũ. Backup và áp dụng V20 theo quy trình
deployment thông thường; lượt triển khai code chỉ kiểm chứng schema test cô lập.

Ngoài phạm vi: gộp/tách/hoán đổi, payment/refund/invoice, đóng phiên/bill, KDS,
QR ordering, sửa Flutter/Web. Hướng dẫn FE: [table-session-transfer-fe-sync.md](table-session-transfer-fe-sync.md).
