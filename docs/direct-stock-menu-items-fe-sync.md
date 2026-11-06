# Hàng bán sẵn theo dõi tồn trực tiếp (V40)

Một MENU_ITEM có thể vừa xuất hiện trên thực đơn vừa là đối tượng tồn kho.
Không cần tạo INGREDIENT hoặc recipe 1:1. Cấu hình cũ tiếp tục dùng được.

## Cấu hình trên app/web quản trị

- NONE: trackInventory=false, recipeEnabled=false.
- DIRECT: trackInventory=true, recipeEnabled=false.
- RECIPE: trackInventory=false, recipeEnabled=true.
- Cả hai true: HTTP 409 INVENTORY_TRACKING_MODE_CONFLICT.

Create bỏ trackInventory mặc định false; PATCH bỏ/null giữ nguyên.
inventoryMode trong response menu là read-only. Không gửi field này trong request.
Tồn kho không đồng nghĩa availabilityStatus; màn hình vẫn sử dụng khả năng bán hiện tại.

Mọi API dùng Authorization: Bearer <staffAccessToken>, Accept: application/json.
Mutation JSON thêm Content-Type: application/json. Tenant lấy từ JWT.

POST /api/v1/menu/items

    {"name":"Coca lon","sku":"COCA-LON","baseUnit":"lon","salePrice":15000,"trackInventory":true,"recipeEnabled":false,"active":true}

Response 201 là MenuItemResponse hiện tại, thêm trackInventory=true và inventoryMode=DIRECT.
Giá vốn có thể chưa cấu hình: costConfigured=false không có nghĩa giá vốn đã biết bằng 0.
Create cần MENU_CREATE + MENU_MANAGEMENT; bật DIRECT cần INVENTORY_MANAGEMENT.
PATCH /api/v1/menu/items/{id} cần MENU_UPDATE + MENU_MANAGEMENT, body expectedVersion hiện tại.
Sửa tên/giá không đòi feature kho khi gói đã mất inventory. DIRECT không cần RECIPE_MANAGEMENT.

Không cho tắt DIRECT, đổi đơn vị hoặc xóa khi có tồn, reservation hoặc phiếu kho DRAFT.
409 STOCK_TRACKING_CHANGE_NOT_ALLOWED / STOCK_ITEM_UNIT_CHANGE_NOT_ALLOWED /
STOCK_ITEM_DELETE_NOT_ALLOWED: xử lý dependency trước rồi reload version.
Conflict version vẫn dùng code hiện tại của endpoint. Không tự retry mutation với version mới.
Chuyển DIRECT sang RECIPE cần gửi trạng thái cuối hợp lệ và có active recipe hợp lệ.

## Chọn hàng để nhập

GET /api/v1/inventory/stock-items?q=Coca&itemType=MENU_ITEM&page=0&size=20

Permission INVENTORY_VIEW, feature INVENTORY_MANAGEMENT. Cache-Control: no-store.
Trả PageResponse: content, page, size, totalElements, totalPages.
Mỗi content gồm itemId, itemType, sku, name, baseUnit, active.
itemType tùy chọn INGREDIENT hoặc MENU_ITEM; size 1..100; q tối đa 100 ký tự.
Picker không có giá vốn hoặc số lượng tồn; balance API đọc tồn theo từng kho.
Hàng inactive vẫn có thể hiện để đối soát, nhưng không được nhập RECEIPT theo rule hiện tại.

## Nhập kho

POST /api/v1/inventory/stock-documents
Idempotency-Key: receipt-coca-001

    {"documentType":"RECEIPT","warehouseId":"<uuid>","items":[{"itemId":"<coca-menu-item-uuid>","quantity":100,"unitCost":7000}]}

Sau đó POST /api/v1/inventory/stock-documents/{documentId}/confirm

    {"expectedVersion":0}

Các permission RECEIVE/ISSUE/ADJUST và guard kho giữ theo controller hiện tại.
Tạo DRAFT chưa tăng tồn. Confirm/replay không ghi stock hai lần.
Chỉ dùng baseUnit: một thùng 24 lon phải nhập quantity=24 khi baseUnit=lon.
Không có quy đổi thùng/lon.

Request ingredientId cũ tiếp tục dùng cho INGREDIENT.
Request mới dùng itemId cho cả hai loại. Nếu gửi cả hai phải cùng UUID;
legacy ingredientId không dùng để biểu diễn MENU_ITEM. Sai/missing ID: 400 INVALID_STOCK_ITEM_REFERENCE.
Hash tạo chứng từ chuẩn hóa theo stock item ID; giữ nguyên thuật toán hash cũ của request nguyên liệu.
Không tái sử dụng một Idempotency-Key cho payload khác.

Response chứng từ, balance, ledger, kiểm kê và cảnh báo bổ sung itemId/itemType.
Với INGREDIENT: ingredientId cũ giữ nguyên. Với MENU_ITEM: ingredientId=null;
FE dùng itemId làm identity, tên/SKU/unit giữ dữ liệu mô tả theo contract.
Các filter/path parameter tên cũ ingredientId của API balance/ledger tiếp tục nhận UUID stock item; dùng itemId trong dữ liệu FE và truyền sang tên parameter hiện tại khi lọc.
Tên cột ingredient_id vật lý ở một số bảng được giữ để tương thích.
Các nhãn field ingredientName/ingredientSku cũ là nhãn tương thích; FE hiển thị nhãn chung “Hàng hóa”.

## Gửi món và xử lý kho

Tiếp tục dùng API order/QR hiện tại, không có API đặt đơn riêng cho DIRECT.
- DRAFT/cart/QR pending/review: không giữ kho.
- SUBMIT/confirm/append SUBMIT/QR approve: chốt quyết định và giữ kho.
- Thiếu tồn: 409 INSUFFICIENT_AVAILABLE_STOCK; toàn command rollback, kể cả phiên mới.
- Kho mặc định thiếu/inactive: 409 INVENTORY_DEFAULT_WAREHOUSE_REQUIRED.
- PENDING -> COOKING hoặc bulk PENDING -> SERVED: consume một lần.
- mark-ready giữ rule hiện tại: món phải bắt đầu nấu; không thêm đường tắt mới.
- READY -> SERVED, payment, complete: không trừ lần nữa.
- Hủy RESERVED: giải phóng phần giữ, không tăng tồn vật lý.
- CONSUMED không tự hoàn kho khi hủy; xử lý theo rule hủy món hiện tại.

Ví dụ nhập 100, nhận 3: quantity=100, reservedQuantity=3, availableQuantity=97.
Sau phục vụ: quantity=97, reservedQuantity=0, availableQuantity=97.
Nếu hủy trước thực hiện: quantity=100, reservedQuantity=0, availableQuantity=100.

GET /api/v1/inventory/orders/{orderId}/items/{orderItemId}/allocation
DIRECT cần ORDER_VIEW + INVENTORY_VIEW, ORDER_MANAGEMENT + INVENTORY_MANAGEMENT.
Không cần RECIPE_VIEW/RECIPE_MANAGEMENT cho DIRECT. Nhánh không DIRECT giữ guard recipe hiện tại.
Response thêm directStockSnapshot; inventoryMode=DIRECT cho nhánh này.
Danh sách ingredients là tên field cũ của reservation, mỗi dòng DIRECT có itemId,
itemType=MENU_ITEM, ingredientId=null, recipeUsageId=null. Không có recipe giả.
API allocation không lộ unitCost. API đọc monetary snapshot vẫn giữ guard DETAIL_REPORT.

## Giá vốn và lịch sử

Confirm RECEIPT cập nhật costPrice của chính món bằng moving weighted average hiện tại.
Không sửa salePrice. Tồn đầu kỳ có giá chưa biết giữ policy unknown opening cost hiện tại.
Acceptance chốt monetary snapshot từ item (costSource=MANUAL theo contract hiện tại),
độc lập DETAIL_REPORT. Giá có thể do chủ quán đặt hoặc được tính lại qua nhập kho.
DIRECT_USE dùng frozen unit cost, không đọc giá hiện tại. Unknown là null; explicit zero là 0.
Recipe report/cohort và CASHBOOK không đổi cách tính. Phiếu nhập không tự tạo phiếu chi.

Snapshot và allocation quyết định consume/release theo lịch sử. Đổi gói/cấu hình sau đó
không làm đơn cũ có hoặc mất reservation. Thiếu INVENTORY_MANAGEMENT lúc acceptance:
SKIPPED/INVENTORY_FEATURE_DISABLED, bán bình thường, không truy hồi tồn khi nâng gói.

## Cảnh báo, kiểm kê và realtime

Threshold/low-stock/stock audit hỗ trợ cả INGREDIENT và DIRECT MENU_ITEM.
Ngưỡng vẫn dựa trên tồn vật lý; minQuantity=0 không tạo cảnh báo.
Event INVENTORY_LOW_STOCK_ALERT_CHANGED giữ envelope hiện tại, thêm payload.itemId/itemType;
ingredientId=null cho MENU_ITEM. Dùng event để reload REST sau commit.
Không phát lại event thành công khi rollback hoặc replay no-op.
Public QR response/event không nhận stock quantity, reservation hoặc giá vốn nội bộ.

App/web cần cập nhật parser nullable ingredientId và enum DIRECT_USE trước khi cho tạo DIRECT.
Sau mutation reload menu/balance/order theo phạm vi hiện có. Không tính tồn ở client.
Không chuyển tự động cấu hình Coca nguyên liệu + recipe cũ sang DIRECT.
