# Giữ chỗ và tiêu thụ nguyên liệu theo order — Giai đoạn 5

Các API ORDER/KDS/QR giữ nguyên URL, body và quyền. Backend tự giữ chỗ khi món
được chấp nhận, tiêu thụ khi bắt đầu thực hiện và giải phóng khi hủy PENDING.
Không có API public reserve/consume/release hoặc sửa reservedQuantity.

## Ý nghĩa dữ liệu

| Dữ liệu | Ý nghĩa |
| --- | --- |
| Recipe snapshot | Công thức và requiredQuantity bất biến tại lúc gửi món |
| RESERVED | Đã giữ nguyên liệu, chưa giảm tồn vật lý |
| CONSUMED / RECIPE_USE | Đã giảm tồn vật lý đúng lượng từng reservation |
| RELEASED | Đã trả phần giữ chỗ; không tăng tồn vật lý |
| SKIPPED | Quyết định không áp dụng kho được lưu bền vững |

`quantity` của balance luôn là tồn vật lý. `reservedQuantity` là tổng reservation
còn RESERVED và `availableQuantity = quantity - reservedQuantity`. Các giá trị
không âm và reservedQuantity không vượt quantity. `lowStock` vẫn so sánh
quantity/minQuantity, không đổi sang availableQuantity.

Ví dụ nhập 1000 g, gửi món cần 315 g: balance là 1000/315/685. Bắt đầu nấu:
685/0/685 và một RECIPE_USE -315. Hủy món trước nấu: 1000/0/1000, không có
RECIPE_USE. Các số theo thứ tự physical/reserved/available.

## Lúc nào giữ chỗ?

Create SUBMIT, confirm OPEN, append SUBMIT và staff approve QR chốt snapshot rồi
giữ chỗ trong cùng transaction. Append vào OPEN gửi mọi dòng PENDING chưa gửi;
append vào order đang phục vụ chỉ áp dụng dòng mới. DRAFT, sửa DRAFT, giỏ khách,
QR pending/review/reject/cancel/expire và GET không giữ chỗ.

Chỉ reserve khi có INVENTORY_MANAGEMENT lúc chấp nhận món, snapshot CAPTURED và
có usage trackInventory=true. Dùng nguyên requiredQuantity đã CEILING trong
snapshot; không tính lại hao hụt hoặc đọc active recipe. Dòng không áp dụng vẫn
có allocation SKIPPED với skipReason:

- INVENTORY_FEATURE_DISABLED.
- SNAPSHOT_NO_RECIPE.
- SNAPSHOT_FEATURE_DISABLED.
- NO_TRACKED_INGREDIENTS.
- LEGACY_UNAVAILABLE.

SKIPPED không được reserve muộn sau nâng package, bật tracking hoặc thêm recipe.
Chỉ dòng mới đánh giá lại. Mỗi allocation dùng một kho default active, chưa xóa;
không chia kho hoặc lấy bù kho khác. Chỉ dòng thực sự cần reserve mới cần kho.
Đổi default/đổi bàn không chuyển allocation cũ; gọi thêm có thể dùng default mới.

PENDING -> COOKING và bulk PENDING -> SERVED consume. COOKING -> READY và
COOKING/READY -> SERVED không trừ lần hai. Single mark-served vẫn chỉ READY ->
SERVED; bulk mark-ready vẫn từ chối nếu có PENDING. Cancel PENDING release;
không mở quyền hủy món đã nấu. Payment/complete/refund không trừ hoặc trả kho.
Complete từ chối khi còn RESERVED hoặc dữ liệu phục vụ không nhất quán.

Consume/release dùng allocation đã chốt kể cả khi mất feature recipe/inventory.
Nhân viên vẫn chỉ cần quyền command ORDER/KDS/QR tương ứng, không cần thêm
INVENTORY_VIEW/ISSUE/ADJUST hoặc RECIPE_VIEW cho side effect nội bộ.

## API quản trị đọc allocation

```http
GET /api/v1/inventory/orders/{orderId}/items/{orderItemId}/allocation
Authorization: Bearer <accessToken>
Accept: application/json
```

Không có body. Cần đồng thời ORDER_VIEW, RECIPE_VIEW, INVENTORY_VIEW và feature
ORDER_MANAGEMENT, RECIPE_MANAGEMENT, INVENTORY_MANAGEMENT, cùng các guard account,
restaurant/subscription hiện có. Tenant lấy từ JWT; item phải thuộc đúng order.
Response `200`, `Cache-Control: no-store`. Không mở endpoint cho public QR.

Response gồm allocation ID/status/skipReason, orderId/orderItemId, kho đã chốt,
reservedAt/consumedAt/releasedAt và danh sách nguyên liệu/quantity đã chốt. Không
trả giá vốn thực tế. DRAFT chưa gửi trả NOT_SUBMITTED response-only, không ghi DB.
SKIPPED trả reason đã lưu và không có reservation detail. Không nhúng các chi tiết
này vào order thường, KDS hay public QR.

Ví dụ 200 RESERVED:

```json
{
  "allocationId": "11111111-1111-4111-8111-111111111111",
  "orderId": "22222222-2222-4222-8222-222222222222",
  "orderItemId": "33333333-3333-4333-8333-333333333333",
  "recipeSnapshotId": "44444444-4444-4444-8444-444444444444",
  "status": "RESERVED",
  "skipReason": null,
  "warehouseId": "55555555-5555-4555-8555-555555555555",
  "reservedAt": "2026-10-20T08:00:00Z",
  "consumedAt": null,
  "releasedAt": null,
  "ingredients": [{
    "reservationId": "66666666-6666-4666-8666-666666666666",
    "recipeUsageId": "77777777-7777-4777-8777-777777777777",
    "ingredientId": "88888888-8888-4888-8888-888888888888",
    "sku": "BEEF",
    "name": "Thịt bò",
    "unit": "g",
    "quantity": 315.000
  }]
}
```

`GET /api/v1/inventory/transactions` tiếp tục cần INVENTORY_VIEW và
INVENTORY_MANAGEMENT; hỗ trợ filter orderId/orderItemId cùng paging/sort cũ.
sourceType là STOCK_DOCUMENT hoặc ORDER_ITEM. RECIPE_USE có orderId/orderItemId/
reservationId, stockDocumentId/documentCode/documentType=null và unitCost=null
(chưa định giá). Quantity change âm; beforeQuantity/afterQuantity là tồn vật lý.
Nguồn chứng từ giữ nguyên field, giá và lịch sử cũ. Giá tham chiếu ingredient từ
RECEIPT khác giá vốn thực tế của order; không dùng null như giá 0 hay báo lãi thực.

## Lỗi

Business conflict trả 409 theo error envelope hiện có. UI dùng code, không parse
message. Lỗi gửi món không tiết lộ recipe, giá hoặc toàn bộ số dư cho waiter/guest.

| Code | Cách xử lý |
| --- | --- |
| INVENTORY_DEFAULT_WAREHOUSE_REQUIRED | Owner cấu hình kho mặc định hợp lệ, rồi thử lại |
| INSUFFICIENT_AVAILABLE_STOCK | Reload order/balance; bổ sung tồn hoặc thay lựa chọn theo luồng hiện có |
| INVENTORY_ALLOCATION_NOT_FOUND | Dữ liệu dòng đã gửi thiếu quyết định; cần kiểm tra backend |
| INVALID_INVENTORY_ALLOCATION_TRANSITION / INVENTORY_ALLOCATION_CONFLICT | Reload và kiểm tra trạng thái, không tự sửa lịch sử |
| INVENTORY_RESERVATION_INVARIANT_VIOLATION | Kiểm tra nhất quán; không tự trừ hoặc reserve bù |
| INVENTORY_QUANTITY_OUT_OF_RANGE | Nhu cầu riêng/tổng vượt precision được hỗ trợ |
| WAREHOUSE_HAS_RESERVATIONS / INGREDIENT_HAS_RESERVATIONS | Xử lý các món đang giữ chỗ trước thay đổi bị chặn |
| CONCURRENT_INVENTORY_UPDATE | Reload trước retry |

Thiếu quyền/feature trả 403. Tenant khác hoặc item sai order trả 404, không lộ
resource. Thiếu tồn rollback toàn bộ command, kể cả session mới, item, snapshot,
allocation, history, audit và idempotency. QR approve lỗi vẫn PENDING; sau bổ sung
tồn, review/approve lại theo version/hash/quote guard hiện có.

## Postman: đủ tồn, thiếu tồn, consume, cancel và replay

1. Bật các feature cần thiết. Tạo tracked ingredient g và recipe cho 10 phần,
   quantity=1000, wastePercent=5; tạo kho đầu tiên và confirm RECEIPT 1000 g.
2. POST `/api/v1/orders` với key ổn định, body sau:

```json
{
  "serviceType": "TAKEAWAY",
  "sourceChannel": "CASHIER",
  "submissionMode": "SUBMIT",
  "items": [{"itemId": "<menuItemId>", "quantity": 3}]
}
```

3. Đọc balance: quantity=1000, reservedQuantity=315, availableQuantity=685.
   Đọc allocation bằng owner: RESERVED. Gửi lại đúng key/body: cùng order, không
   thêm allocation/reservation/audit, balance vẫn như trên.
4. POST `/api/v1/orders/{orderId}/items/{orderItemId}/start-cooking` body
   `{"expectedOrderVersion": <version>}`. Balance thành 685/0/685, allocation
   CONSUMED, ledger có một ORDER_ITEM/RECIPE_USE -315 với unitCost=null. Retry
   transition theo replay rule hiện có không ghi thêm ledger/audit.
5. Tạo order mới 3 phần và POST `/api/v1/orders/{orderId}/cancel` body
   `{"expectedVersion": <version>, "reason": "Khách hủy"}` khi món PENDING.
   Allocation RELEASED, physical vẫn 685 và reserved=0. Retry cancel không giảm
   reserved lần hai. Có thể kiểm cancel-line bằng endpoint ORDER hiện có.
6. Gửi order 7 phần khi available chỉ 685: 409 INSUFFICIENT_AVAILABLE_STOCK,
   không để lại order/items/snapshot/reservation. Với QR, guest gửi/review vẫn
   thành công nhưng chưa giữ kho; approve thiếu tồn vẫn để request PENDING.
7. Confirm RECEIPT bổ sung, reload/review rồi thử lại. Giữ nguyên key/body cho
   retry cùng intent; không tự thay hash QR khi chưa review/confirm lại.

Đồng bộ FE: [order-inventory-allocation-fe-sync.md](order-inventory-allocation-fe-sync.md).
Thiết kế và migration: [order-inventory-allocation-implementation.md](order-inventory-allocation-implementation.md).
OpenAPI: [tenant-api.yaml](openapi/tenant-api.yaml).
