# Triển khai snapshot giá vốn tham chiếu lịch sử — Giai đoạn 2

ORDER sở hữu reference cost đã chốt cho dòng accepted; MENU sở hữu giá/cấu hình
hiện tại; INVENTORY sở hữu reservation, consume/release và append-only ledger.
Đây chưa phải actual COGS, FIFO, full inventory valuation hoặc báo cáo lợi nhuận.
Snapshot là quyết định giá tại acceptance, tách biệt ghi nhận tiêu thụ.

## Model và invariant

Mở rộng hai bảng hiện có, không tạo bảng cost trùng lặp:

| Bảng | Cột mới | Kiểu SQL |
| --- | --- | --- |
| order_item_recipe_snapshots | cost_snapshot_version | SMALLINT NULL |
| order_item_recipe_snapshots | cost_status | VARCHAR(30) NULL |
| order_item_recipe_snapshots | cost_unavailable_reason | VARCHAR(60) NULL |
| order_item_recipe_snapshots | unit_cost_snapshot, total_cost_snapshot | NUMERIC(38,2) NULL |
| order_item_recipe_usages | cost_configured_snapshot | BOOLEAN NULL |
| order_item_recipe_usages | unit_cost_snapshot | NUMERIC(14,2) NULL |
| order_item_recipe_usages | line_cost_snapshot | NUMERIC(38,2) NULL |

Version 1 quy định công thức tính tiền. `costStatus` tách biệt recipe
`snapshotStatus`; persisted status chỉ COMPLETE/INCOMPLETE/UNAVAILABLE. Java dùng
`OrderCostSnapshot` và `OrderIngredientCostSnapshot` để mang kết quả đã tính,
không thêm cost mode chỉnh độc lập với `costSourceSnapshot`.

COMPLETE có hai số tiền không âm, reason null. Configured zero hợp lệ. INCOMPLETE
và UNAVAILABLE có hai số tiền null, reason theo nguồn. Usage configured=true có
giá/lineCost kể cả zero; false có hai money field null. Header recipe INCOMPLETE
vẫn giữ tiền của từng usage configured=true. Mọi usage, gồm untracked, được định
giá để đánh giá tổng đầy đủ.

## Calculator và batch ports

`OrderReferenceCostCalculator` thuần BigDecimal, không truy cập database. MANUAL
lấy giá của chính MENU_ITEM; RECIPE dùng lượng usage đã chốt:

```text
manual.total = round(manual.unit × order.quantity, 2)
usage.line = round(usage.requiredQuantity × usage.unit, 2)
recipe.total = SUM(usage.line đã làm tròn)
recipe.unit = round(recipe.total / order.quantity, 2)
```

Tiền HALF_UP scale 2. requiredQuantity đã gồm recipe/waste/quantity/yield và
CEILING scale 3; không tính lại waste/yield. Recipe total authoritative, presentation
unit không được dùng để tính lại total. Không tái dùng estimator tính theo batch:
live cost-estimate giữ contract hiện có, có thể khác do lượng kho đã CEILING và
thứ tự rounding. Calculator kiểm identity/unit/giá/currency/precision trước persist.
Giá nguồn theo currency nhà hàng lúc acceptance; currency response từ order bất
biến. Mismatch/overflow là conflict và rollback, không coi là thiếu cấu hình giá.

| Consumer-owned port | Adapter / trách nhiệm |
| --- | --- |
| ORDER: OrderReferenceCostQuery | MENU OrderReferenceCostAdapter đọc manual MENU_ITEM và ingredient reference prices theo tenant/distinct IDs; immutable Prices/Price DTO có currency, identity, unit, configured state |
| INVENTORY: RecipeUsageCostQuery | ORDER InventoryRecipeUsageCostAdapter đọc batch frozen usage/header bằng recipeUsageIds; mang order/line/header/ingredient/unit/quantity/version/configured/price để xác minh reservation |

`OrderCostSnapshotPreparation` gom fresh inputs, gọi mỗi nguồn theo distinct IDs
và tính mọi header/usage trước insert. FEATURE_DISABLED không đọc recipe hoặc
manual fallback. Không gọi API cost-estimate và không yêu cầu người acceptance
có quyền đọc giá/công thức/kho. ORDER/INVENTORY không import MENU repository/entity;
INVENTORY không import ORDER repository/entity để đọc giá.

Mandatory permission/feature guards của command chạy trước live recipe/price
lookup và calculation; giữ lock ordering hiện có. Capture đọc một
CurrentEntitlement cho quyết định recipe rồi trả cùng object cho operation reserve
để chọn inventory eligibility. Các coarse guards bên ngoài vẫn tồn tại; đây không
phải cache toàn cục hay cam kết mọi command chỉ có một entitlement query.
DETAIL_REPORT chỉ gate API đọc, không gate capture/consume. Đọc và validate lịch
sử inventory dùng frozen facts, không query entitlement.

## Capture, transaction, audit và replay

`OrderRecipeSnapshotService` dùng acceptance operation hiện có cho create SUBMIT,
confirm DRAFT, append SUBMIT và hai nhánh QR approve. Chỉ fresh lines được đọc giá.
Chuẩn bị quantities và monetary results trước `snapshots.saveAllAndFlush`, sau đó
insert usages và audit; không UPDATE header đã bất biến.

Tất cả chạy đồng bộ cùng transaction command dưới restaurant mutation lock,
serialize với manual price update và receipt repricing. Dùng timestamp/Clock của
command, không Instant.now(), REQUIRES_NEW, async hoặc AFTER_COMMIT để capture.
Order/session/items/header/usages/audit/reservation/QR linkage cùng commit/rollback.
DRAFT/cart/QR PENDING/review/reject/GET/consume/release/payment/complete không capture.

Một audit `ORDER_ITEM_RECIPE_SNAPSHOTTED` cho mỗi fresh monetary decision tích hợp
vào action hiện có, thêm version/status/reason/unit/total vào summary cùng source
và recipe metadata. Không tạo action/audit thứ hai chỉ vì monetary snapshot;
INCOMPLETE/UNAVAILABLE được ghi đúng. Audit failure rollback acceptance. Replay/no-op
không đọc giá live, sửa snapshot hoặc ghi audit trùng; legacy không được bổ sung tiền.

Root version và canonical event giữ mutation boundary hiện có. Không có realtime
event mới và không thêm cost vào ORDER_CHANGED, OrderResponse, KDS/public QR/menu.
Gọi thêm luôn tạo dòng mới; giá hiện tại không gộp đè dòng đã accepted.

## Consume và kiểm tra lịch sử INVENTORY

`OrderInventoryAllocationAdapter` đọc frozen usage costs theo batch qua
`RecipeUsageCostQuery`. Nó kiểm tenant/linkage/order/line/header/ingredient/unit/
requiredQuantity và monetary version trước tạo RECIPE_USE hoặc validate history.
`InventoryAllocationPolicy.history()` và `InventoryTransaction.recipeUse()` chấp
nhận đúng frozen price thay cho quy tắc cũ luôn null:

- Version 1 + configured=true: ledger unitCost bằng đúng usage.unitCostSnapshot,
  gồm 0.00, kể cả header INCOMPLETE vì ingredient khác thiếu giá.
- Version 1 + configured=false: ledger unitCost=null.
- Header version=null: legacy ledger unitCost=null; không đọc catalogue hôm nay.

Chỉ tracked usages đã reservation được consume. MANUAL/untracked không sinh ledger.
Không sửa ledger cũ. Cancel PENDING release reserved, không tăng physical; không
tự hoàn kho món đã consume. Snapshot tiền giữ sau cancel. Thiếu giá không chặn
cooking/serve/payment/complete hợp lệ. Validation vẫn chặn inconsistency, duplicate
consume và sai linkage; payment/complete/retry không tiêu thụ lần hai.

## Migration V38 và database guards

`V38__snapshot_order_item_reference_costs.sql` chỉ thêm cột và thay CHECK/function
cần thiết; V1–V37 giữ bytes. Không UPDATE monetary data hoặc ledger, không disable
immutable/append-only trigger, không backfill từ catalogue. Mọi row cũ có toàn bộ
cột mới null; version=null nhận diện lịch sử chưa có monetary decision, kể cả
CAPTURED với source metadata của V37.

CHECK bảo vệ nguồn/status/reason/money và phương trình MANUAL/usage. BEFORE INSERT
bắt fresh regular header version=1, cấm giả legacy bằng version=null; vẫn chấp
nhận explicit LEGACY_UNAVAILABLE không metadata cho maintenance lịch sử, ứng dụng
không có writer tạo mới loại này. Usage insert guard giữ tenant/status và cùng
creation_transaction_id; v1 usage bắt configured state, legacy không có monetary
fields. Không thể thêm usages vào header CAPTURED cũ đã commit.

Deferred `order_recipe_snapshot_requires_usages` giữ yêu cầu CAPTURED có usages và
bổ sung kiểm toàn bộ configured/sum/rounded-unit cho COMPLETE, có ít nhất một
unknown cho INCOMPLETE. Header được insert trước details trong cùng transaction;
không cần mutation để cộng tổng.

Chỉ nhánh ORDER_ITEM/RECIPE_USE của `ck_inventory_transaction_source` được mở cho
giá không âm. BEFORE INSERT `guard_inventory_recipe_use` giữ mọi kiểm reservation/
identity/quantity/unit cũ và so unit_cost bằng frozen usage price; unknown/legacy
bắt null. Tenant FK, uniqueness, append-only ledger và deferred allocation/terminal
history tiếp tục giữ nguyên. Legacy RESERVED vẫn consume/release, CONSUMED vẫn
validate/complete/replay theo dữ liệu đã chốt.

## API và bảo mật

`OrderCostSnapshotController` → `OrderCostSnapshotQueryService` chỉ đọc header;
`OrderCostSnapshotAccess` kiểm hai permission ORDER_VIEW/REPORT_DETAIL_VIEW và hai
feature ORDER_MANAGEMENT/DETAIL_REPORT. Không thêm package/feature/role grants.
Access đọc entitlement một lần và kiểm đủ cả hai feature trước query order/line/
snapshot; không đọc dữ liệu trước rồi mới từ chối quyền.
Tenant khác/item sai order theo 404 hiện có. Missing header của submitted line
giữ conflict, không tự capture. Summary không mở recipe detail; recipe-snapshot
giữ guard và DTO không tiền hiện có. GET không ghi audit/version/data.

[API/Postman](order-reference-cost-snapshot-api.md) ·
[Đồng bộ FE](order-reference-cost-snapshot-fe-sync.md) ·
[OpenAPI](openapi/order-api.yaml)

## Kiểm thử và phạm vi

Migration tests có clean latest V38, nâng cấp V37 với CAPTURED/RESERVED/CONSUMED và
source decisions cũ; so toàn bộ facts trước/sau khi bỏ đúng cột mới. Guard tests
kiểm fresh version/source/status/amount, known/zero/unknown, usage rounding,
aggregate deferred, tenant FK, exact ledger price, immutability và legacy consume/
release. Kết quả bàn giao V38: 1257 test passed trên PostgreSQL test riêng, không failures/errors/skips.
Các kết quả lịch sử trong tài liệu giai đoạn cũ không đại diện xác minh V38.

Tests dùng database/container riêng với DB_URL và credentials tường minh, schema
ngẫu nhiên đồng bộ Flyway/Hibernate. Chỉ cleanup schema/container đã xác minh
ownership; không H2, không mount hay chạy vào database làm việc.

Ngoài phạm vi: dashboard/report lợi nhuận, FIFO/lô, revaluation hoặc điều chỉnh
snapshot, quy đổi đơn vị/currency, operating expenses/ca, refund/tự hoàn kho,
frontend implementation và realtime/grant mới.


Full Maven verification: 1257 tests passed, no failures/errors/skips. See [verification and changed-file inventory](order-reference-cost-snapshot-verification.md).
