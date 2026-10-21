# Ingredient catalog API

Module này quản lý danh mục nguyên liệu của restaurant trên cùng bảng `items`, với
`item_type=INGREDIENT`. Đây chưa phải module kho: `costPrice` chỉ là giá vốn tham
chiếu và `trackInventory=true` không tự tạo số dư tồn.
Khi xác nhận phiếu nhập `RECEIPT` trong module INVENTORY, nguyên liệu theo dõi tồn
được cập nhật `costPrice` bình quân trên tổng tồn các kho cùng restaurant. Chỉ khi
giá đổi mới tăng version nguyên liệu và ghi `INGREDIENT_COST_RECALCULATED`.
FE cần tải lại detail/version sau confirm; xem công thức và quy tắc giá 0 tại
inventory-api.md. Không tự đổi giá bán hoặc giá vốn món ăn.

## Điều kiện truy cập chung

- Base URL: `/api/v1/menu/ingredients`.
- Header: `Authorization: Bearer <accessToken>` và `Accept: application/json`.
- Request có body thêm `Content-Type: application/json`.
- Tenant lấy từ JWT; không gửi `restaurantId` trong header, URL hay body.
- Tài khoản/restaurant phải active, subscription phải effective.
- Immutable subscription snapshot phải có ít nhất một trong
  `RECIPE_MANAGEMENT` hoặc `INVENTORY_MANAGEMENT`.
- Permission được kiểm tra theo từng endpoint, không suy ra từ role/package name.

V28 thêm `INGREDIENT_VIEW`, `INGREDIENT_CREATE`, `INGREDIENT_UPDATE` và
`INGREDIENT_DELETE` vào module permission `INVENTORY`. OWNER và SUPER_ADMIN hệ
thống được grant mặc định; MANAGER, WAITER, CASHIER và KITCHEN không được tự grant.
JWT cũ cần đăng nhập/refresh theo cơ chế hiện tại để nhận authority mới.

## 1. Danh sách

```http
GET /api/v1/menu/ingredients?q=thit&active=true&trackInventory=true&page=0&size=20&sortBy=createdAt&direction=desc
Authorization: Bearer <accessToken>
Accept: application/json
```

Permission: `INGREDIENT_VIEW`.

Query:

- `q`: tối đa 100, tìm không phân biệt hoa thường theo `name` hoặc `sku`; `%`, `_`
  và `\` được coi là ký tự thường.
- `active`, `trackInventory`: boolean tùy chọn.
- `page >= 0`, mặc định 0; `size` 1–100, mặc định 20.
- `sortBy`: `createdAt`, `updatedAt`, `name`, `sku`, `costPrice`.
- `direction`: `asc` hoặc `desc`; mặc định `createdAt desc`.
- `id` luôn là tie-breaker cuối.

Response `200`:

```json
{
  "content": [
    {
      "id": "11111111-1111-4111-8111-111111111111",
      "sku": "NL-THIT-BO",
      "name": "Thịt bò",
      "baseUnit": "g",
      "description": "Thịt bò dùng cho phở",
      "costPrice": 250.00,
      "trackInventory": true,
      "active": true,
      "version": 0,
      "createdAt": "2026-10-16T08:00:00Z",
      "updatedAt": "2026-10-16T08:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

## 2. Chi tiết

```http
GET /api/v1/menu/ingredients/{ingredientId}
Authorization: Bearer <accessToken>
Accept: application/json
```

Permission: `INGREDIENT_VIEW`. Response `200` là một `IngredientResponse` như
phần tử trong danh sách. ID đã xóa, sai tenant hoặc là `MENU_ITEM` trả
`404 INGREDIENT_NOT_FOUND`.

## 3. Tạo nguyên liệu

```http
POST /api/v1/menu/ingredients
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: application/json
```

Permission: `INGREDIENT_CREATE`.

```json
{
  "sku": "NL-THIT-BO",
  "name": "Thịt bò",
  "baseUnit": "g",
  "description": "Thịt bò dùng cho phở",
  "costPrice": 250.00,
  "trackInventory": true,
  "active": true
}
```

`name` và `baseUnit` bắt buộc. `costPrice` mặc định 0,
`trackInventory` mặc định false, `active` mặc định true. SKU được trim/uppercase;
blank thành null và phải unique trong toàn catalog cùng tenant, kể cả MENU_ITEM đã
xóa mềm. Giá không âm, tối đa `NUMERIC(14,2)` và không được tự làm tròn quá hai số
thập phân. Response `201` là `IngredientResponse`.

Backend cố định `itemType=INGREDIENT`, `groupId=null`, `salePrice=0`,
`imageUrl=null`, `availabilityStatus=AVAILABLE`, `metadata={}`. Client không được
gửi các field này hoặc field tenant/audit/version.

## 4. Cập nhật thông tin

```http
PATCH /api/v1/menu/ingredients/{ingredientId}
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: application/json
```

Permission: `INGREDIENT_UPDATE`.

```json
{
  "name": "Thịt bò Úc",
  "costPrice": 280.00,
  "trackInventory": true,
  "expectedVersion": 0
}
```

Field sửa được: `sku`, `name`, `baseUnit`, `description`, `costPrice`,
`trackInventory`; `expectedVersion` bắt buộc và không âm. Null/omitted giữ nguyên;
chuỗi rỗng ở `sku`/`description` xóa thành null. Body chỉ có version trả
`400 EMPTY_UPDATE_REQUEST`. Thay đổi thực tế tăng version và ghi audit; cập nhật
ra đúng giá trị hiện tại là no-op, không đổi timestamp/version/audit.
API sửa `costPrice` thủ công vẫn giữ nguyên; giá đã sửa được dùng làm giá tham chiếu
cũ cho lần nhập tiếp theo. Không chỉnh ngược giá trong ledger hay snapshot đơn cũ.
Nếu nguyên liệu đang được công thức active dùng, đổi `baseUnit` trả
`409 INGREDIENT_UNIT_IN_USE`; các field còn lại vẫn sửa được.
V33 còn chặn đổi baseUnit hoặc bỏ trackInventory khi có reservation RESERVED:
`409 INGREDIENT_HAS_RESERVATIONS`. Đổi tên/SKU/cost không sửa reservation cũ.

## 5. Bật/tắt

```http
PATCH /api/v1/menu/ingredients/{ingredientId}/status
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: application/json

{"active":false,"expectedVersion":1}
```

Permission: `INGREDIENT_UPDATE`. Response `200`. Gọi lại cùng trạng thái là
idempotent kể cả version cũ; không ghi audit trùng. Thay đổi thật ghi
`INGREDIENT_ENABLED` hoặc `INGREDIENT_DISABLED`.
Disable nguyên liệu đang được công thức active dùng trả
`409 INGREDIENT_USED_BY_ACTIVE_RECIPE`.
Reservation RESERVED cũng chặn deactivate: `409 INGREDIENT_HAS_RESERVATIONS`.

## 6. Xóa mềm

```http
DELETE /api/v1/menu/ingredients/{ingredientId}?expectedVersion=2
Authorization: Bearer <accessToken>
Accept: application/json
```

Permission: `INGREDIENT_DELETE`. Response `204`, không body. Xóa lần hai cùng
tenant vẫn `204`, không audit trùng. SKU vẫn được giữ chỗ. Bản ghi không còn xuất
hiện trong list/detail và không bị hard delete.
Xóa nguyên liệu đang được công thức active dùng trả
`409 INGREDIENT_USED_BY_ACTIVE_RECIPE`. Nếu chỉ còn recipe history inactive thì
vẫn xóa được và snapshot lịch sử không thay đổi.
Nếu còn reservation RESERVED, trả `409 INGREDIENT_HAS_RESERVATIONS`; không xóa
lịch sử để vượt guard. Guard nonzero stock vẫn áp dụng.

## Lỗi và concurrency

Error envelope dùng contract chung. Các code chính:

- `400 VALIDATION_ERROR`, `INVALID_REQUEST_BODY`, `EMPTY_UPDATE_REQUEST`.
- `401 UNAUTHORIZED`, `ACCOUNT_INACTIVE`.
- `403 FORBIDDEN`, `TENANT_ACCESS_DENIED`, `SUBSCRIPTION_NOT_ACTIVE`,
  `FEATURE_NOT_ENTITLED`.
- `404 INGREDIENT_NOT_FOUND`.
- `409 SKU_EXISTS`, `CONCURRENT_INGREDIENT_UPDATE`,
  `INVALID_INGREDIENT_TRANSITION`, `INGREDIENT_USED_BY_ACTIVE_RECIPE`,
  `INGREDIENT_UNIT_IN_USE`, `INGREDIENT_HAS_STOCK`, `INGREDIENT_HAS_RESERVATIONS`.

Mutation lock restaurant và chạy persistence + audit cùng transaction.
`expectedVersion` chống ghi đè form cũ; conflict persistence được chuyển thành mã
ổn định và không lộ SQL/constraint.

## Giới hạn hiện tại

Đã có recipe version/snapshot, kho/chứng từ và allocation theo order; xem
[inventory API](inventory-api.md) và [allocation API](order-inventory-allocation-api.md).
Chưa có unit conversion, kiểm kê, nhà cung cấp hoặc persistent alert. Ingredient không bán trực tiếp,
không xuất hiện ở menu nhân viên, public restaurant menu hay QR menu; order/QR
order vẫn chỉ nhận `MENU_ITEM`.
