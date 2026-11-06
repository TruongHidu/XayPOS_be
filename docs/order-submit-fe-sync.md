# Contract và prompt đồng bộ ORDER cho Flutter / Cashier Web

Backend đã bổ sung SUBMIT cho nhân viên. Không có public QR đặt món, KDS, thanh toán, đóng bill/phiên hay trừ kho. Menu QR khách hàng vẫn chỉ đọc. Trong lượt BE này không sửa frontend.

## Contract

### Tạo từ tableId: chỉ mở phiên khi Gửi đơn

Vẫn POST /api/v1/orders, không thêm /orders/from-table. DINE_IN nhận đúng một
trong tableId hoặc tableSessionId. tableId bắt buộc SUBMIT tường minh; absent/null
mode vẫn DRAFT và bị từ chối cho tableId. Legacy tableSessionId DRAFT/SUBMIT và
TAKEAWAY không có seating giữ nguyên.

```json
{
  "serviceType": "DINE_IN",
  "sourceChannel": "WAITER",
  "tableId": "<UUID bàn>",
  "submissionMode": "SUBMIT",
  "items": [{"itemId": "<UUID món>", "quantity": 1, "note": null}]
}
```

- Chọn bàn/chọn món chỉ lưu giỏ local, không gọi API mở phiên.
- Bàn chưa có phiên: ORDER_CREATE + ORDER_UPDATE + TABLE_OPEN; backend mở phiên
  và order CONFIRMED cùng transaction. Thất bại không để lại phiên rỗng.
- Bàn có phiên OPEN nhưng chưa có serving order: chỉ cần các quyền ORDER trên;
  không cần TABLE_OPEN, không sửa phiên cũ.
- Có serving order: mở detail qua active-order, gọi thêm vào đúng orderId.
- Feature ORDER_MANAGEMENT + TABLE_MANAGEMENT cho DINE_IN; permission/feature
  API đọc bàn/menu chọn món vẫn riêng, không dùng public menu để bypass.
- Body gửi từ bàn không có tableSessionId/expectedVersion. Response trả
  tableSessionId thật, status CONFIRMED, version authoritative; không confirm HTTP lần hai.
- Một create intent freeze tenant/user + tableId + toàn bộ ordered payload + key.
  Retry không thay tableId bằng sessionId mới lấy từ GET. Replay trả order gốc,
  không trả order mới đang phục vụ tại bàn và không cần mở phiên lại.
- Retry unknown outcome phải độc lập với gate của fresh intent: TABLE_OPEN chỉ
  cần khi thực tế mở mới, không chặn retry chỉ vì bàn/phiên/quyền TABLE_OPEN đã đổi.
  Current ORDER permissions/features và scope vẫn được kiểm ở backend.

400 AMBIGUOUS_ORDER_SEATING / TABLE_ORDER_REQUIRES_SUBMIT /
TAKEAWAY_TABLE_NOT_ALLOWED: sửa input, không retry mù.
409 TABLE_SESSION_HAS_SERVING_ORDER: refetch để đối chiếu, giữ giỏ; không tự append,
chuyển target hoặc đổi key. 409 TABLE_ALREADY_OCCUPIED: tải lại trạng thái bàn.

### Bổ sung V19: một serving order/session

Create DINE_IN bằng key mới khi có OPEN/CONFIRMED/PREPARING/READY/SERVED trả
409 TABLE_SESSION_HAS_SERVING_ORDER, không tạo order khác hoặc auto-append.
OPEN draft cũng chiếm slot. CANCELLED/COMPLETED là lịch sử, không chiếm slot.
Hash/replay/body/header legacy create và append giữ nguyên; tableId có namespace
hash create-table-submit-v1 riêng, không có endpoint hoặc response wrapper mới.

FE cần dùng GET /api/v1/table-sessions/{sessionId}/active-order:

- Header Authorization Bearer + Accept application/json, permission ORDER_VIEW,
  feature ORDER_MANAGEMENT effective snapshot; không cần MENU_VIEW để xem.
- 200 OrderResponse: mở detail và gọi thêm vào đúng id, canAppend vẫn riêng.
- 204 không body: chưa có serving order; chỉ create nếu đủ quyền và session OPEN.
- 404 session foreign/missing; 409 INCONSISTENT_SERVING_ORDER_STATE là dữ liệu lỗi,
  không chọn latest/first hoặc tự merge.

Ẩn “Tạo đơn mới trong phiên” khi serving order tồn tại; vẫn có lịch sử phân trang.
409 khi race: refetch active order để đối chiếu, không tự chuyển giỏ hoặc đổi key.
Create replay order A đã cancel vẫn trả A, dù B hiện đang serving.
Frozen append retry/version/snapshot/cancel boundary không thay đổi.
Backend không có /orders/from-table: tạo từ bàn dùng POST /orders hiện có.
Payment/KDS/session closure vẫn chưa triển khai.
Lượt này chỉ xuất handoff, không sửa app/web.

Mọi request dùng Authorization: Bearer <accessToken>, Content-Type: application/json và tenant từ principal. Không gửi restaurantId/createdBy/amounts/status/unitPrice.

| Command | Body mới | Quyền | Kết quả |
| --- | --- | --- | --- |
| POST /api/v1/orders | Create hiện có + optional tableId, submissionMode | DRAFT: ORDER_CREATE; SUBMIT: ORDER_CREATE + ORDER_UPDATE; mở phiên mới: thêm TABLE_OPEN | Mới 201, replay 200; DRAFT OPEN, SUBMIT CONFIRMED |
| POST /api/v1/orders/{id}/items | expectedVersion, items, submissionMode | ORDER_UPDATE | 200; DRAFT chỉ OPEN, SUBMIT OPEN/CONFIRMED |
| POST /api/v1/orders/{id}/confirm | expectedVersion (không đổi) | ORDER_UPDATE | Luồng draft cũ; confirmed retry no-op |

submissionMode absent/null = DRAFT; chỉ DRAFT/SUBMIT, invalid trả 400 INVALID_REQUEST_BODY. Create luôn cần Idempotency-Key; append SUBMIT cũng bắt buộc key riêng cho một lần gọi thêm. DRAFT append không idempotent bằng key, chỉ bảo vệ bởi expectedVersion. Response giữ đầy đủ OrderResponse hiện có, thêm Idempotency-Replayed cho append. Cache-Control=no-store; header replay đã được CORS expose.

Feature: ORDER_MANAGEMENT mọi command. Create/append SUBMIT DINE_IN cần TABLE_MANAGEMENT; TAKEAWAY cần POS_QUICK_ORDER. Menu chọn món có gate riêng hiện có; không suy quyền từ role/source hoặc package name.

Create SUBMIT body:

```json
{
  "serviceType": "TAKEAWAY",
  "sourceChannel": "CASHIER",
  "tableSessionId": null,
  "submissionMode": "SUBMIT",
  "items": [{"itemId": "<UUID>", "quantity": 1, "note": null}]
}
```

Append SUBMIT body:

```json
{
  "expectedVersion": 5,
  "submissionMode": "SUBMIT",
  "items": [{"itemId": "<UUID>", "quantity": 1, "note": "Ít cay"}]
}
```

Root CONFIRMED, UNPAID/paid=0; lines PENDING, sentToKitchenAt=null. Không hiển thị “Đang nấu” hoặc “Đã gửi bếp”. OPEN append+SUBMIT rechecks mọi món live; CONFIRMED append giữ snapshots/statuses/confirmedAt cũ. Metadata/quantity/note cũ vẫn OPEN-only. Sau auto-confirm, hủy line kể cả line vừa thêm cần ORDER_CANCEL.

## Prompt triển khai FE

```text
Hãy đồng bộ ORDER với backend docs/order-api.md và docs/openapi/order-api.yaml. Đọc implementation và cấu trúc FE trước khi sửa. Giữ kiến trúc hiện tại, DI, domain policy/use-case/repository, auth/tenant scope và xử lý tiền chính xác. Không đổi backend hoặc QR menu read-only.

1. Khảo sát và báo file cần sửa. Giữ worktree người dùng; không thay state-management/library/architecture hoặc phần ngoài ORDER.

2. DTO/schema/input bổ sung optional tableId, giữ submissionMode DRAFT|SUBMIT. DINE_IN đúng một tableId/sessionId; tableId chỉ SUBMIT, TAKEAWAY không có cả hai. Response vẫn OrderResponse; không tự đặt status/version/confirmedAt. HTTP append truyền Idempotency-Key và đọc Idempotency-Replayed tương tự create. Không log key/token/PII.

3. Nút chính “Gửi đơn” dùng SUBMIT, cần ORDER_CREATE + ORDER_UPDATE và feature theo serviceType; nếu phải mở phiên mới cần TABLE_OPEN. Chọn bàn/chọn món không mở phiên. Gửi DINE_IN từ bàn dùng tableId, backend resolve phiên trong transaction. Legacy tạo nháp chỉ từ phiên đã có (hoặc TAKEAWAY), không tự mở phiên để lưu nháp từ bàn trống và không silently downgrade. Create SUBMIT thành công không gọi confirm HTTP thứ hai. TAKEAWAY không query bàn/phiên. Source CASHIER/WAITER không tạo quyền.

4. Giữ canEdit/confirmable cũ. Tạo canAppend riêng: UPDATE + ORDER_MANAGEMENT + type feature, root OPEN/CONFIRMED, UNPAID/paid=0; seating hợp lệ nếu biết. Server quyết định cuối cùng. Không mở canEdit để sửa metadata/old lines trên CONFIRMED. Cancel boundary vẫn dựa confirmedAt, không cấp mặc định CANCEL cho CASHIER/WAITER.

5. Gọi thêm tại bàn: phiên -> GET active-order -> detail -> thêm món vào id duy nhất. Không dùng latest/first trong danh sách lịch sử; TAKEAWAY vẫn chọn order cụ thể. Tạo mới chỉ khi không có serving order, không fallback create khi append bị từ chối.

6. Tách giỏ gọi thêm khỏi saved snapshots. Gửi chỉ new lines; không merge old lines kể cả cùng itemId/note. Giá cart là preview, totals/snapshots lấy response. Không reset/reprice old lines hoặc tự sửa occupancy.

7. Một append intent lưu immutable scope(tenant/user), targetId, baseline expectedVersion, SUBMIT, ordered items và stable key. Disable double-click. Timeout/network/5xx: retry đúng key + toàn bộ frozen payload, không thay expectedVersion bằng GET mới. Không chặn replay chỉ vì cached version/status/menu/seating đã đổi; chỉ kiểm auth/scope trước retry frozen intent. Tách retry của unknown outcome khỏi canAppend/new-mutation version guard. Không auto-retry PATCH/cancel legacy thiếu ledger.

7b. Create từ bàn cũng có frozen intent riêng chứa tableId, không chứa sessionId resolve sau request. Retry giữ nguyên body/key ngay cả khi cache bàn hiện có phiên/order khác. GET active-order không chứng minh create intent đã áp dụng. Thành công/replay mới xóa giỏ intent và dùng tableSessionId/orderId từ response; không tự đổi sang append. Scope switch/logout phải xử lý theo cơ chế auth hiện tại, không gửi intent của tài khoản cũ.

8. IDEMPOTENCY_KEY_REUSED: kiểm tra intent, không tự tạo key mới. CONCURRENT_ORDER_UPDATE: refetch, giữ giỏ để người dùng so sánh và quyết định intent mới, không blind retry. 403/401 giữ auth handler; không bypass. 409 menu/seating/currency/payment/state: tải lại và hiển thị business error. GET hiện trạng không tự chứng minh intent đã áp dụng; resolve bằng key/payload gốc.

9. Refresh token: giữ intent trong cùng user/tenant theo behavior hiện có. Logout/switch account/tenant xóa cache và intent riêng tư, không gửi sang scope khác. Không auto-submit khi remount/refresh. Không lưu token/PII/intent vào localStorage trái chính sách hiện có.

10. Thành công/replay: thay detail bằng server response; invalidate order lists và session/service workspace liên quan. Không tự tăng version (operation gộp có thể tăng hơn một lần), mở/đóng phiên hoặc sửa occupancy local. Xóa uncertain guard đúng sau response authoritative.

11. Tests: chọn bàn rồi thoát không gọi open/create; tableId chỉ SUBMIT; create thành công dùng sessionId server trả; thiếu TABLE_OPEN chỉ chặn fresh mở phiên; replay không phụ thuộc live seating; giữ tableId/key/payload sau timeout; 409 không tự append giỏ. Giữ absent/DRAFT legacy, hai quyền SUBMIT, canAppend khác canEdit, snapshots, cancel boundary, scope/refresh-token và invalidation regression. Chạy test/analyzer/lint/build hiện có, báo baseline riêng.
```

## Các vùng FE cần kiểm tra khi đồng bộ (không sửa trong lượt BE)

Flutter:

- `lib/features/order/domain/policies/order_access_policy.dart`: canAppend riêng; giữ canEdit/lineCancelPermission.
- Create/AddItems input, remote/repository/use-case: mode/key/result header.
- `order_draft_controller.dart`: thêm tableId vào frozen create intent, bỏ mở phiên ngay lúc chọn bàn; giữ SUBMIT và xử lý retry hiện có.
- `order_detail_controller.dart`: giữ frozen append intent/retry riêng, không fallback create khi thêm món bị từ chối.
- Workspace bàn/order detail: dùng current session + active-order, giữ selected orderId.

Cashier Web:

- `src/features/order/api/ordersApi.ts`, types/schemas: append key/replay/mode.
- `policies/orderActionPolicy.ts`: canAppend riêng, không nới editable.
- `hooks/useOrderMutations.ts`: add đang chung editable/cached-version check; tách new mutation và retry intent.
- `store/orderDraftStore.ts`, `store/orderOperationState.ts`, OrderPanel/OrderAddItems/OrderEditor: frozen payload, một bước, selected root.
- `src/test/orderUi.test.tsx`: kiểm tableId deferred seating và frozen replay; giữ test DRAFT/SUBMIT/cancel/scope/token regression.
