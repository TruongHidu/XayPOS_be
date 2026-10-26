# Inventory API — Giai đoạn 1–7

Giai đoạn 7 thêm [kiểm kê kho](stock-audit-api.md): DRAFT -> COUNTING -> CONFIRMED,
hoặc CANCELLED. Các API `/api/v1/inventory/audits` dùng INVENTORY_MANAGEMENT +
STOCK_AUDIT; GET cần INVENTORY_VIEW, quản lý cần thêm STOCK_AUDIT_MANAGE, chốt cần
thêm STOCK_AUDIT_CONFIRM. Không khóa kho suốt COUNTING; biến động vật lý sau start
trả STOCK_AUDIT_SNAPSHOT_STALE, phải hủy và đếm lại. Confirm bảo vệ reserved hiện tại.
Stock document GET/list thêm stockAuditId nullable; generated ADJUSTMENT chỉ đọc,
mọi mutation public trả STOCK_DOCUMENT_MANAGED_BY_AUDIT. Public create từ chối
namespace SYS_STOCK_AUDIT: (trim, không phân biệt hoa/thường). Không đổi ledger source.
Giá tiền dòng chứng từ làm tròn HALF_UP 2 số khi quantity có 3 số thập phân.


Giai đoạn 6 thêm [cảnh báo có lịch sử và acknowledge](low-stock-alert-api.md):
GET `/api/v1/inventory/alerts`, GET `/api/v1/inventory/alerts/{alertId}` và POST
`/api/v1/inventory/alerts/{alertId}/acknowledge`. GET cần INVENTORY_VIEW; ACK cần
thêm INVENTORY_ALERT_ACKNOWLEDGE. Cả ba cần INVENTORY_MANAGEMENT+LOW_STOCK_ALERT.
GET `/low-stock` cũ vẫn là danh sách số dư thấp, không đổi response hay ghi DB.

Giai đoạn 5 bổ sung [giữ chỗ/tiêu thụ nguyên liệu theo order](order-inventory-allocation-api.md)
và [hướng dẫn đồng bộ FE](order-inventory-allocation-fe-sync.md). Đây là side effect
của ORDER/KDS/QR; không có API public reserve/consume/release.

Base URL: `/api/v1/inventory`. Tenant luôn lấy từ access JWT; không nhận
`restaurantId` từ client. Mọi API cần subscription effective có
`INVENTORY_MANAGEMENT`; riêng `GET /low-stock` cần thêm `LOW_STOCK_ALERT`.

Header chung:

```http
Authorization: Bearer <accessToken>
Accept: application/json
Content-Type: application/json   # mutation có JSON body
```

## Warehouse

| Method | URL | Permission | Body / query | Response |
| --- | --- | --- | --- | --- |
| GET | `/warehouses` | `INVENTORY_VIEW` | `q`, `active`, `page`, `size`, `sortBy=code\|name\|createdAt\|updatedAt`, `direction` | `PageResponse<WarehouseResponse>` |
| GET | `/warehouses/{warehouseId}` | `INVENTORY_VIEW` | — | `200 WarehouseResponse` |
| POST | `/warehouses` | `WAREHOUSE_CREATE` | `{"code":"KHO-CHINH","name":"Kho chính"}` | `201 WarehouseResponse` |
| PATCH | `/warehouses/{warehouseId}` | `WAREHOUSE_UPDATE` | `{"expectedVersion":0,"code":"KHO-TONG","name":"Kho tổng"}` | `200 WarehouseResponse` |
| PATCH | `/warehouses/{warehouseId}/status` | `WAREHOUSE_UPDATE` | `{"expectedVersion":1,"active":false}` | `200 WarehouseResponse` |
| POST | `/warehouses/{warehouseId}/make-default` | `WAREHOUSE_UPDATE` | `{"expectedVersion":1}` | `200 WarehouseResponse` |
| DELETE | `/warehouses/{warehouseId}?expectedVersion=1` | `WAREHOUSE_DELETE` | — | `204` |

Ví dụ warehouse:

```json
{
  "id": "5fb90ce1-e28d-4be1-a3b5-7d97a0f598f0",
  "code": "KHO-CHINH",
  "name": "Kho chính",
  "isDefault": true,
  "active": true,
  "version": 0,
  "createdAt": "2026-10-18T08:00:00Z",
  "updatedAt": "2026-10-18T08:00:00Z"
}
```

Kho đầu tiên tự active/default. Kho sau active nhưng không default. `code` được
trim/uppercase và vẫn bị giữ chỗ sau soft-delete. Không thể disable default hoặc
disable/delete kho còn `quantity != 0` hoặc reservation RESERVED
(`409 WAREHOUSE_HAS_RESERVATIONS`). Chuyển default là atomic và không chuyển
reservation cũ sang kho mới. Desired state
đã đạt và delete lặp lại là no-op, không tăng version/audit.

## Balance và threshold

| Method | URL | Permission | Ghi chú |
| --- | --- | --- | --- |
| GET | `/warehouses/{warehouseId}/balances` | `INVENTORY_VIEW` | `q`, `ingredientActive`, paging; sort `ingredientName`, `sku`, `quantity`, `minQuantity`, `updatedAt` |
| GET | `/warehouses/{warehouseId}/balances/{ingredientId}` | `INVENTORY_VIEW` | Có thể trả logical zero balance |
| PATCH | `/warehouses/{warehouseId}/balances/{ingredientId}/threshold` | `INVENTORY_THRESHOLD_UPDATE` | Chỉ cập nhật `minQuantity`, không nhận `quantity` |
| GET | `/low-stock` | `INVENTORY_VIEW` | Cần thêm feature `LOW_STOCK_ALERT`; filter optional `warehouseId`, `q`, `ingredientActive` |

Threshold lần đầu:

```json
{
  "expectedVersion": null,
  "minQuantity": 10.000
}
```

Threshold đã có row:

```json
{
  "expectedVersion": 0,
  "minQuantity": 15.000
}
```

Balance dùng mô hình sparse. Tracked ingredient chưa có row vẫn được đọc như sau;
GET không ghi database:

```json
{
  "balanceId": null,
  "configured": false,
  "warehouseId": "5fb90ce1-e28d-4be1-a3b5-7d97a0f598f0",
  "warehouseCode": "KHO-CHINH",
  "ingredientId": "3c630fa2-3abc-4980-855f-cf72a00a1dd5",
  "ingredientSku": "NL-THIT-BO",
  "ingredientName": "Thịt bò",
  "baseUnit": "g",
  "ingredientActive": true,
  "quantity": 0.000,
  "reservedQuantity": 0.000,
  "availableQuantity": 0.000,
  "minQuantity": 0.000,
  "lowStock": false,
  "version": null,
  "createdAt": null,
  "updatedAt": null
}
```

`minQuantity=0` tắt cảnh báo. Low stock chỉ đúng khi `minQuantity > 0` và
`quantity <= minQuantity`. Ingredient inactive vẫn được đọc nếu chưa xóa và còn
`trackInventory=true`.

`quantity` giữ nghĩa tồn vật lý. `reservedQuantity` là lượng giữ cho món chưa
tiêu thụ, `availableQuantity=quantity-reservedQuantity`; cả ba chỉ đọc và không
âm. Client không gửi quantity/reservedQuantity để sửa tồn. Logical zero vẫn không
tạo row. `lowStock` và `/low-stock` tiếp tục dùng quantity/minQuantity.

## Concurrency, audit và giới hạn

Mọi mutation khóa restaurant, dùng `@Version` và `expectedVersion`. Tạo threshold
từ logical balance chỉ chấp nhận version null; row đã có bắt buộc version khác null.
Conflict trả `409 CONCURRENT_WAREHOUSE_UPDATE` hoặc
`CONCURRENT_INVENTORY_UPDATE`.

Audit: `WAREHOUSE_CREATED`, `WAREHOUSE_UPDATED`, `WAREHOUSE_ENABLED`,
`WAREHOUSE_DISABLED`, `WAREHOUSE_DEFAULT_CHANGED`, `WAREHOUSE_DELETED`,
`INVENTORY_THRESHOLD_UPDATED`. Request lỗi/no-op không ghi audit.

Client không sửa trực tiếp quantity/reservedQuantity. Physical thay đổi khi confirm
chứng từ hoặc consume order; mỗi biến động physical có ledger. Reserve/release
chỉ đổi reserved và có allocation/audit, không ghi RECIPE_USE.

Ingredient còn tồn khác 0 không thể bỏ tracking, đổi `baseUnit` hoặc soft-delete;
API trả `409 INGREDIENT_HAS_STOCK`. Guard công thức hiện có vẫn được giữ nguyên.
Ingredient còn reservation RESERVED không được đổi unit, bỏ tracking,
deactivate/delete: `409 INGREDIENT_HAS_RESERVATIONS`.

## Stock document — Phase 2

Chứng từ hỗ trợ `RECEIPT`, `ISSUE`, `ADJUSTMENT` và vòng đời
`DRAFT -> CONFIRMED | CANCELLED`. Chỉ `CONFIRMED` mới cập nhật tồn. Chứng từ đã
confirmed/cancelled không được sửa hay xóa. Mọi endpoint vẫn cần
`INVENTORY_MANAGEMENT`; quyền mutation phụ thuộc loại chứng từ:

| Loại | Quyền |
| --- | --- |
| `RECEIPT` | `INVENTORY_RECEIVE` |
| `ISSUE` | `INVENTORY_ISSUE` |
| `ADJUSTMENT` | `INVENTORY_ADJUST` |

| Method | URL | Body/query | Response |
| --- | --- | --- | --- |
| GET | `/stock-documents` | `q`, `type`, `status`, `warehouseId`, `createdFrom`, `createdTo`, paging | `PageResponse<StockDocumentSummaryResponse>` |
| GET | `/stock-documents/{documentId}` | — | `StockDocumentDetailResponse` |
| POST | `/stock-documents` | Header `Idempotency-Key`; create body | `201 StockDocumentDetailResponse` |
| PATCH | `/stock-documents/{documentId}` | `expectedVersion`, `note` | `200 StockDocumentDetailResponse` |
| POST | `/stock-documents/{documentId}/items` | `expectedVersion` và một dòng | `200 StockDocumentDetailResponse` |
| PATCH | `/stock-documents/{documentId}/items/{documentItemId}` | `expectedVersion` và dữ liệu dòng | `200 StockDocumentDetailResponse` |
| DELETE | `/stock-documents/{documentId}/items/{documentItemId}` | query `expectedVersion` | `204` |
| POST | `/stock-documents/{documentId}/confirm` | `{"expectedVersion":1}` | `200 StockDocumentDetailResponse` |
| POST | `/stock-documents/{documentId}/cancel` | `{"expectedVersion":1}` | `200 StockDocumentDetailResponse` |
| GET | `/transactions` | `warehouseId`, `ingredientId`, `documentId`, `orderId`, `orderItemId`, `transactionType`, dates, paging | `PageResponse<InventoryTransactionResponse>` |
| GET | `/orders/{orderId}/items/{orderItemId}/allocation` | Không body; ba quyền/feature theo [allocation API](order-inventory-allocation-api.md) | Allocation bất biến; no-store |

Ví dụ tạo phiếu nhập:

```http
POST /api/v1/inventory/stock-documents
Authorization: Bearer <accessToken>
Idempotency-Key: receipt-mobile-20261018-001
Content-Type: application/json
```

```json
{
  "documentType": "RECEIPT",
  "warehouseId": "5fb90ce1-e28d-4be1-a3b5-7d97a0f598f0",
  "note": "Nhập đầu ca",
  "items": [
    {
      "ingredientId": "3c630fa2-3abc-4980-855f-cf72a00a1dd5",
      "quantity": 10.500,
      "unitCost": 12000.00,
      "note": "Lô sáng"
    }
  ]
}
```

`adjustmentDirection` phải để trống cho `RECEIPT`/`ISSUE`; với `ADJUSTMENT` bắt
buộc là `INCREASE` hoặc `DECREASE`. `unitCost` mặc định `0`. Ingredient phải cùng
tenant, là `INGREDIENT`, có `trackInventory=true`; receipt còn yêu cầu ingredient
active. Đơn vị là snapshot từ `baseUnit`, client không được gửi đơn vị.

```json
{
  "id": "706211ea-2b21-4e53-aee9-c63935761654",
  "documentCode": "PN-20261018-000001",
  "documentType": "RECEIPT",
  "status": "DRAFT",
  "warehouseId": "5fb90ce1-e28d-4be1-a3b5-7d97a0f598f0",
  "warehouseCode": "KHO-CHINH",
  "warehouseName": "Kho chính",
  "note": "Nhập đầu ca",
  "lineCount": 1,
  "totalQuantity": 10.500,
  "totalCost": 126000.00,
  "items": [{
    "id": "2d841786-736d-4e80-8b17-875a5261f0ba",
    "ingredientId": "3c630fa2-3abc-4980-855f-cf72a00a1dd5",
    "ingredientSku": "NL-GAO",
    "ingredientName": "Gạo",
    "unit": "kg",
    "quantity": 10.500,
    "adjustmentDirection": null,
    "unitCost": 12000.00,
    "totalCost": 126000.00,
    "note": "Lô sáng"
  }],
  "version": 1
}
```

`Idempotency-Key` được scope theo restaurant. Gửi lại cùng key và cùng payload trả
chính chứng từ đã tạo; cùng key khác payload trả `409 IDEMPOTENCY_KEY_REUSED`.
Confirm/cancel lặp lại trên đúng trạng thái đích là no-op và không tạo ledger/audit
trùng. Khi confirm, backend khóa restaurant, chứng từ và toàn bộ balance theo thứ tự
ingredient; một dòng xuất/giảm không được làm physical thấp hơn reserved. Vi phạm
rollback toàn bộ với `409 INSUFFICIENT_AVAILABLE_STOCK`. RECEIPT/ADJUSTMENT tăng
physical giữ nguyên reserved và tăng available tương ứng.

### Giá vốn nguyên liệu khi xác nhận phiếu nhập

API và request/response giữ nguyên. Chỉ `RECEIPT` được confirm lần đầu mới cập nhật
`INGREDIENT.costPrice` theo bình quân liên hoàn:

```text
newCost = (oldQuantity × oldCost + receiptQuantity × receiptUnitCost)
          / (oldQuantity + receiptQuantity)
```

- `oldQuantity` là tổng tồn vật lý TRƯỚC nhập của nguyên liệu trong tất cả kho cùng
  restaurant, kể cả kho inactive; không dùng riêng tồn kho đích hay lượng available.
- Tồn bằng 0 (kể cả chưa có balance) thì giá mới bằng đơn giá nhập. Đơn vị phải đúng
  `baseUnit`; không tự quy đổi kg/g. Giá được làm tròn `HALF_UP` hai chữ số một lần
  ở kết quả cuối, phù hợp `costPrice` hiện tại.
- Ví dụ tồn 10 kg giá 100.000 đ/kg, nhập 5 kg giá 130.000 đ/kg: tồn 15 kg và
  `costPrice=110000.00` đ/kg.
- `unitCost=0` vẫn hợp lệ; bỏ `unitCost` vẫn mặc định 0 theo contract cũ. Một lần
  nhập giá 0 CÓ tham gia bình quân và có thể giảm giá vốn. FE cần hiển thị/cảnh báo
  giá nhập 0, không coi 0 là “không cập nhật giá”. Tồn cũ có `costPrice=0` cũng
  được tính với giá trị 0; cần khởi tạo/sửa giá tham chiếu nếu thực tế chưa định giá.
- DRAFT, chỉnh/hủy DRAFT, ISSUE và ADJUSTMENT không tự tính lại giá. Chứng từ cũ đã
  CONFIRMED không được tính bù; replay confirm là no-op kể cả đã có lần nhập mới.
- Giữ khóa restaurant hiện tại; khóa ingredient theo thứ tự ID trước khi đọc tổng
  tồn và cập nhật giá. Giá, version/timestamp nguyên liệu, balance, ledger, trạng
  thái chứng từ và audit cùng transaction: lỗi bất kỳ rollback toàn bộ.
- Khi giá thực sự thay đổi, ghi `INGREDIENT_COST_RECALCULATED`, before/after giá và
  `sourceDocumentId`, `costMethod=MOVING_WEIGHTED_AVERAGE`. Nếu giá không đổi thì
  không tăng version nguyên liệu hay tạo audit giá thừa. Permission xác nhận phiếu
  vẫn là `INVENTORY_RECEIVE`; không yêu cầu thêm `INGREDIENT_UPDATE`.
- Không đổi giá bán, `MENU_ITEM.costPrice`, snapshot công thức/đơn hoặc ledger cũ.
  Ledger phiếu nhập vẫn lưu đúng `unitCost` của lần nhập, không đổi thành giá bình
  quân. Lần GET recipe cost-estimate tiếp theo đọc giá tham chiếu mới.

Test Postman: confirm RECEIPT 10 đơn vị × 100, rồi confirm RECEIPT 5 đơn vị × 130;
GET `/api/v1/menu/ingredients/{ingredientId}` phải có `costPrice=110.00`. Đọc lại
ingredient để lấy `version` mới trước khi sửa thông tin nguyên liệu. Gửi lại confirm
phiếu thứ hai: giá vẫn 110, tồn vẫn 15, không thêm ledger/audit giá.

Đây là giá tham chiếu cập nhật từ nhập kho, không phải sổ định giá kế toán đầy đủ.
API sửa giá nguyên liệu thủ công vẫn giữ nguyên; giá đã sửa là cơ sở cho lần nhập
tiếp theo. Order đã áp dụng inventory giảm physical khi bắt đầu thực hiện; reserve
và release không đổi trọng số bình quân. Chưa xử lý FIFO, landed cost, revaluation
hay giá vốn thực tế của order.

Sổ `/transactions` là append-only và lưu `quantityChange`, `beforeQuantity`,
`afterQuantity`, unit/cost cùng snapshot tên/SKU từ nguồn đã chốt.
`sourceType=STOCK_DOCUMENT` giữ các field chứng từ cũ. `sourceType=ORDER_ITEM`
có transactionType=RECIPE_USE, orderId/orderItemId/reservationId; các field chứng
từ null và unitCost=null nghĩa chưa định giá, không phải 0. RECIPE_USE.quantityChange
âm; before/after là physical. Không dùng ledger chưa định giá để tính lợi nhuận.
Các lỗi
chính: `STOCK_DOCUMENT_NOT_FOUND`, `STOCK_DOCUMENT_ITEM_NOT_FOUND`,
`INVALID_STOCK_DOCUMENT_TRANSITION`, `STOCK_DOCUMENT_EMPTY`,
`DUPLICATE_STOCK_DOCUMENT_ITEM`, `INVALID_ADJUSTMENT_DIRECTION`,
`WAREHOUSE_INACTIVE`, `INGREDIENT_INACTIVE_FOR_RECEIPT`, `INGREDIENT_UNIT_CHANGED`,
`INSUFFICIENT_STOCK`, `CONCURRENT_STOCK_DOCUMENT_UPDATE` và
`CONCURRENT_INVENTORY_UPDATE`. Dữ liệu đầu vào tính giá vốn không hợp lệ trả
`409 INVALID_INVENTORY_COST_DATA` và rollback toàn bộ.

Chưa có transfer, kiểm kê, supplier, unit conversion, full inventory valuation,
hoàn tác chứng từ confirmed hay sửa/xóa ledger. Giai đoạn 6 đã bổ sung persistent
low-stock alert; [WebSocket/STOMP](inventory-alert-realtime.md) bổ sung thông báo
thay đổi cảnh báo sau commit cho client đang kết nối, không có offline replay.

V32 bổ sung [snapshot công thức tại lúc gửi món](order-recipe-snapshot-api.md) ở
module ORDER. V33 dùng tracked usages đã chốt để reserve ở kho default, consume
thành RECIPE_USE hoặc release khi hủy PENDING. Snapshot không đổi. Dòng đã gửi
trước V33 là SKIPPED/LEGACY_UNAVAILABLE kể cả snapshot CAPTURED; không hồi tố kho.
Giá bình quân ingredient có thể đổi sau RECEIPT mà snapshot/reservation cũ giữ nguyên.
