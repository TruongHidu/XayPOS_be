# Kiểm kê kho — Inventory Giai đoạn 7

Base URL: `/api/v1/inventory/audits`. Tenant lấy từ JWT, không nhận restaurantId.
Tất cả endpoint yêu cầu tài khoản/nhà hàng active, subscription effective và cả hai
feature `INVENTORY_MANAGEMENT` + `STOCK_AUDIT`. Không cần LOW_STOCK_ALERT để kiểm kê;
posting vẫn bảo trì cảnh báo nội bộ như trước.

## Headers và quyền

```http
Authorization: Bearer {{accessToken}}
Accept: application/json
Content-Type: application/json
Idempotency-Key: {{auditCreateKey}}
```

Content-Type dùng khi có JSON body; Idempotency-Key **chỉ bắt buộc ở create**,
trim, dài tối đa 100. Cùng key/payload trả lại cùng audit (201), payload khác 409
IDEMPOTENCY_KEY_REUSED. Key scope tenant + stock audit, độc lập stock document.

| Method | URL (sau base) | Permission (AND) | Body | Thành công |
| --- | --- | --- | --- | --- |
| POST | `/` | INVENTORY_VIEW + STOCK_AUDIT_MANAGE | Create | 201 Detail |
| GET | `/` | INVENTORY_VIEW | Không | 200 Page Summary |
| GET | `/{auditId}` | INVENTORY_VIEW | Không | 200 Detail |
| GET | `/{auditId}/items` | INVENTORY_VIEW | Không | 200 Page Item |
| PATCH | `/{auditId}` | INVENTORY_VIEW + STOCK_AUDIT_MANAGE | Note | 200 Detail |
| POST | `/{auditId}/start` | INVENTORY_VIEW + STOCK_AUDIT_MANAGE | Transition | 200 Detail |
| PATCH | `/{auditId}/items` | INVENTORY_VIEW + STOCK_AUDIT_MANAGE | Counts | 200 Detail |
| POST | `/{auditId}/confirm` | INVENTORY_VIEW + STOCK_AUDIT_CONFIRM | Transition | 200 Detail |
| POST | `/{auditId}/cancel` | INVENTORY_VIEW + STOCK_AUDIT_MANAGE | Note | 200 Detail |

STOCK_AUDIT_MANAGE được V35 seed cho system OWNER/SUPER_ADMIN. STOCK_AUDIT_CONFIRM
giữ seed cũ; staff cần được owner cấp quyền riêng. Không tự cấp quyền mới cho
CASHIER/KITCHEN/WAITER/MANAGER. SUPER_ADMIN không có tenant không thể dùng API tenant.
JWT cũ cần login/refresh để nhận quyền mới. Internal confirm không đòi INVENTORY_ADJUST.

## Luồng nghiệp vụ

`DRAFT -> COUNTING -> CONFIRMED`; `DRAFT/COUNTING -> CANCELLED`.

- Create chỉ tạo header; có thể có nhiều DRAFT cùng kho. Warehouse không đổi được.
- Start chốt **toàn bộ** INGREDIENT tracked, undeleted của tenant, gồm cả inactive,
  trong kho đã chọn. Tồn chưa có row là zero, không tạo balance vì snapshot/GET.
  Một kho chỉ có tối đa một COUNTING; các kho khác độc lập.
- Scope, tên/SKU/unit/tồn vật lý/giá tham chiếu/token ledger bất biến từ start.
  Ingredient mới sau start không tự thêm vào scope. Deactivate không loại dòng.
- `countedQuantity=null`: chưa đếm. `0`: đã đếm và thực tế bằng 0.
- `differenceQuantity = countedQuantity - systemQuantity`, null nếu chưa đếm.
- `quantity` là tồn vật lý; `reservedQuantity` vẫn còn nằm trong kho;
  `availableQuantity = quantity - reservedQuantity`. Số đếm đã bao gồm reserved,
  **không cộng reserved lần nữa**.
- Kho không freeze giữa các request. Reserve/release/đổi threshold hay đổi giá
  đơn thuần không làm stale. Mọi biến động vật lý của dòng thuộc scope (nhập/xuất,
  ADJUSTMENT, RECIPE_USE) sau start đều làm stale, kể cả nhập 5 rồi xuất 5.
- Confirm kiểm tra quantity + COUNT(*) ledger theo tenant/kho/ingredient, unit và
  eligibility hiện tại; đối soát reserved cache với reservation thật ở mọi dòng.
  `countedQuantity >= current reservedQuantity` mới được chốt.
- Stale trả 409 và giữ COUNTING + dữ liệu đếm. Cancel, tạo/start phiên mới và đếm
  lại; không tự refresh/rebase. Nên kiểm kê cuối ca hoặc lúc ít giao dịch.
- Confirm chỉ tạo một ADJUSTMENT CONFIRMED cho các delta khác 0, posting chung
  cập nhật balance/ledger/alerts trong cùng transaction. Delta zero không có line.
  Mọi dòng khớp: CONFIRMED với adjustmentDocumentId=null, không chứng từ rỗng.
- Start COUNTING, confirm CONFIRMED, cancel CANCELLED đều replay desired-state
  trước kiểm tra version; giữ actor/time/snapshot, không ghi audit trùng.

## Body

### Create

`POST {{baseUrl}}/api/v1/inventory/audits`

```json
{"warehouseId":"{{warehouseId}}","note":"Kiểm kê cuối ca"}
```

Warehouse phải active/undeleted cùng tenant. Note optional, trim, blank -> null,
tối đa 2000 ký tự. Create không snapshot, không tạo ledger hoặc thay tồn.

### Start / confirm (Transition)

```json
{"expectedVersion":0}
```

Lấy version mới nhất từ response; version là của **audit**, không phải balance.
Start chỉ DRAFT; confirm chỉ COUNTING và mọi dòng đã đếm. Replay như mô tả trên.

### Sửa note / cancel (Note)

```json
{"expectedVersion":1,"note":"Đếm lại sau khi xuất kho"}
```

Sửa note chỉ DRAFT/COUNTING; null/blank xóa note. Cancel thiếu/null note giữ note
cũ, note không rỗng thay note; không xóa count sheet hay đổi tồn/reservation.
CONFIRMED không được sửa hoặc cancel.

### Batch count (Counts)

```json
{
  "expectedVersion":1,
  "items":[
    {"auditItemId":"{{auditItemId}}","countedQuantity":8.000,"note":"Đếm thực tế"},
    {"auditItemId":"{{anotherAuditItemId}}","countedQuantity":0}
  ]
}
```

1–100 dòng/request; ID **dòng kiểm kê**, không phải ingredientId. ID trùng bị 400;
sai tenant/parent trả STOCK_AUDIT_ITEM_NOT_FOUND và không lưu phần đúng. Quantity
bắt buộc, 0..99999999999.999, tối đa 3 số thập phân. Không nhận unit để quy đổi.
Note dòng max 1000, omit/null giữ cũ, blank xóa. Một batch thực thay đổi tăng header
version đúng một lần; no-op không tăng version/audit nhưng vẫn kiểm tra state/version.
Không có API thêm/xóa dòng scope sau start. Tất cả body từ chối field ngoài contract.

## Query và response

List: `?warehouseId={{warehouseId}}&status=COUNTING&page=0&size=20&sortBy=createdAt&direction=desc`

- q: literal substring auditCode/note, max 100; `%`/`_` không phải wildcard.
- status: DRAFT/COUNTING/CONFIRMED/CANCELLED; không truyền là tất cả.
- createdFrom inclusive, createdTo exclusive: ISO instant; from < to.
- page >=0; size 1–100 mặc định 20.
- sortBy: createdAt (mặc định), startedAt, updatedAt, auditCode.
- direction: asc/desc (không phân biệt hoa/thường), mặc định desc; tie-break id ASC.

Items: `?q=bo&counted=false&differenceOnly=false&page=0&size=100&sortBy=name&direction=asc`

- q: literal substring snapshot name/SKU, max 100.
- counted omitted: tất cả; true đã đếm; false chưa đếm.
- differenceOnly=true: chỉ counted khác system, không gồm dòng chưa đếm.
- sortBy: name (mặc định), sku, systemQuantity, countedQuantity, differenceQuantity.
- direction mặc định asc; NULLS LAST; tie-break id ASC.

Detail không chứa mảng items không giới hạn:

```json
{
  "id":"26df5e65-2c64-4c56-a0f7-0d897288f845",
  "auditCode":"AUD-20261021-aabbccdd",
  "warehouseId":"8a224a83-4b36-4123-95f8-67c665dcdb0a",
  "warehouseCode":"MAIN","warehouseName":"Kho chính",
  "status":"COUNTING","note":"Kiểm kê cuối ca",
  "itemCount":2,"countedItemCount":1,"changedItemCount":1,
  "adjustmentDocumentId":null,"adjustmentDocumentCode":null,
  "version":2,
  "createdBy":"5c4e7a44-b127-4fce-8284-09680cf9d7d1",
  "startedBy":"5c4e7a44-b127-4fce-8284-09680cf9d7d1",
  "confirmedBy":null,"cancelledBy":null,
  "createdAt":"2026-10-21T08:00:00Z","startedAt":"2026-10-21T08:05:00Z",
  "confirmedAt":null,"cancelledAt":null,"updatedAt":"2026-10-21T08:06:00Z"
}
```

Summary có các field Detail ngoại trừ createdBy/startedBy/confirmedBy/cancelledBy,
confirmedAt/cancelledAt. Items đọc snapshot, vẫn xem được sau source soft-delete:

```json
{
  "content":[{
    "id":"64a69e50-1811-45b5-a741-904aa235c803",
    "ingredientId":"af83e9b2-03ed-45d7-b4fa-248d212178fa",
    "sku":"BEEF","name":"Thịt bò","unit":"g",
    "systemQuantity":10.000,"countedQuantity":8.000,"differenceQuantity":-2.000,
    "referenceUnitCostSnapshot":10000.00,"note":"Đếm thực tế"
  }],
  "page":0,"size":20,"totalElements":1,"totalPages":1
}
```

List cũng dùng PageResponse này nhưng content là Summary; GET không tạo balance,
audit hay refresh token snapshot. Giá là **giá tham chiếu tại start**, 0 hợp lệ,
không phải actual COGS. Đổi giá sau start không cập nhật snapshot. ADJUSTMENT dùng
giá này, tiền làm tròn HALF_UP 2 số; không thay weighted cost, giá bán hay order/recipe.

## Generated document và API cũ

Stock document GET/list thêm `stockAuditId` nullable. Server gán nguồn duy nhất,
client không gửi stockAuditId/origin. Bất kỳ mutation public nào lên generated
document đều 409 STOCK_DOCUMENT_MANAGED_BY_AUDIT: sửa header, add/update/delete
line, confirm (kể cả replay), cancel. Public GET/list vẫn đọc được.

Public create stock document từ chối key trim có prefix `SYS_STOCK_AUDIT:` không
phân biệt hoa thường **trước lookup/replay**, 400 RESERVED_IDEMPOTENCY_NAMESPACE.
Internal key `SYS_STOCK_AUDIT:{auditId}`. Migration fail nếu dữ liệu cũ chiếm prefix.
Ledger vẫn sourceType=STOCK_DOCUMENT, ADJUSTMENT_INCREASE/ADJUSTMENT_DECREASE.

## Lỗi

Error envelope dùng chung: `{"success":false,"code":"...","message":"...","fieldErrors":{},"timestamp":"..."}`.

| HTTP | Code / tình huống |
| --- | --- |
| 400 | VALIDATION_ERROR (body/query/precision/duplicate IDs), IDEMPOTENCY_KEY_REQUIRED, RESERVED_IDEMPOTENCY_NAMESPACE |
| 401 | Chưa đăng nhập hoặc account bị vô hiệu theo security filter hiện có |
| 403 | FORBIDDEN, FEATURE_NOT_ENTITLED, SUBSCRIPTION_NOT_ACTIVE, RESTAURANT_INACTIVE |
| 404 | STOCK_AUDIT_NOT_FOUND, STOCK_AUDIT_ITEM_NOT_FOUND; tenant khác cũng 404 |
| 409 | INVALID_STOCK_AUDIT_TRANSITION, CONCURRENT_STOCK_AUDIT_UPDATE, WAREHOUSE_STOCK_AUDIT_IN_PROGRESS |
| 409 | STOCK_AUDIT_EMPTY, STOCK_AUDIT_INCOMPLETE, STOCK_AUDIT_SNAPSHOT_STALE, WAREHOUSE_INACTIVE |
| 409 | INSUFFICIENT_AVAILABLE_STOCK, INVENTORY_RESERVATION_INVARIANT_VIOLATION |
| 409 | STOCK_DOCUMENT_MANAGED_BY_AUDIT, STOCK_AUDIT_ADJUSTMENT_OUT_OF_RANGE, INVALID_INVENTORY_COST_DATA, IDEMPOTENCY_KEY_REUSED |

Không tự coi mọi DB integrity error là đã có COUNTING; lỗi constraint chưa nhận
diện trả server error đã lọc, không SQL/stacktrace.

## Postman: các kịch bản chính

Tạo warehouse + tracked ingredient và nhập tồn bằng API cũ trước. Dùng ID thực,
lưu `auditId`, `auditItemId` từ GET items và `version` mới nhất mỗi mutation.

1. Tồn 10, create/start, count 8; order reserve 6 sau start -> confirm thành công:
   physical=8, reserved=6, available=2, một ADJUSTMENT DECREASE 2.
2. Tồn 10, start/count 8; order reserve 9 -> confirm 409
   INSUFFICIENT_AVAILABLE_STOCK. Không đổi physical/reservation/audit status.
3. Tồn 10, start/count 8; RECEIPT 5 rồi ISSUE 5 -> confirm 409
   STOCK_AUDIT_SNAPSHOT_STALE dù physical quay về 10. Cancel và đếm lại phiên mới.
4. Count mọi dòng bằng system -> CONFIRMED, adjustmentDocumentId=null,
   không thêm stock_document/ledger/balance chỉ vì kiểm kê.
5. Confirm phiên có delta rồi retry bằng version của request confirm đầu ->
   cùng document ID, không thêm ledger/audit. COUNTING start replay không reset count.
6. Gọi public stock-document confirm/update/cancel/generated-line mutation ->
   409 STOCK_DOCUMENT_MANAGED_BY_AUDIT; GET document thấy stockAuditId.
7. Chỉ đổi threshold hoặc reserve/release sau start -> không stale; consume bằng
   start-cooking/bulk mark-served -> stale. Mỗi scenario nên dùng phiên riêng.

Không hỗ trợ unit conversion, freeze warehouse, tự rebase, partial confirm,
reversal, xóa lịch sử, FIFO, actual COGS, realtime/push hoặc sửa package snapshot.
