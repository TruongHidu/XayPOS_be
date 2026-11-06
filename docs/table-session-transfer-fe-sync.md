# Đồng bộ app/web sau chuyển bàn

Chỉ là hướng dẫn; backend change không sửa frontend.

## Luồng giao diện

1. Trong màn chi tiết đơn/bàn, hiển thị “Chuyển bàn” khi có TABLE_TRANSFER và
   TABLE_MANAGEMENT, phiên OPEN. Không kiểm role cứng, không dùng TABLE_OPEN thay thế.
2. Hoàn tất/resolve create hoặc append đang chưa rõ kết quả trước khi bắt đầu transfer.
3. Load phiên hiện tại và danh sách bàn AVAILABLE, occupied=false; bỏ bàn nguồn và
   khu vực inactive. Nạp phân trang đầy đủ phù hợp UI; danh sách có thể stale.
4. Chọn bàn đích, nhập reason tùy chọn, chốt intent:
   actorId, sessionId, targetTableId, session.expectedVersion, reason, Idempotency-Key.
5. Disable thao tác lặp trong lúc gửi POST `/api/v1/table-sessions/{sessionId}/transfer`.
6. Thành công giữ nguyên orderId/sessionId và màn chi tiết hiện tại; cập nhật context
   theo TableSessionResponse mới, không điều hướng sang danh sách đơn hàng.
7. Invalidate/refetch session detail, current-session hai bàn, table workspace,
   badge/occupancy/tổng tiền và transfer history. Nếu server replay trả bàn C thay B,
   lấy C làm context hiện tại; refresh toàn workspace để tránh dữ liệu cũ.
8. Gọi thêm vẫn dùng orderId cũ và endpoint append hiện có. Dùng Order.version mới nhất
   cho append; tuyệt đối không dùng session version để sửa order hoặc ngược lại.

## Timeout và conflict

- Timeout/mất mạng/5xx: giữ intent/key đã chốt; retry **cùng actor/session/target/version/
  reason/key**, không tự đổi version trong request đang retry. Không tạo key mới để
  “thử lại”, không tự cancel/open session, không gọi create order.
- Replay trả representation hiện tại, có thể ở bàn khác hoặc CLOSED/CANCELLED.
  Luôn xử lý response status và refresh context, không ép UI về bàn trong request cũ.
- 409 CONCURRENT_TABLE_UPDATE/TABLE_ALREADY_OCCUPIED/TABLE_INACTIVE/TABLE_AREA_INACTIVE:
  reload, giải thích cho nhân viên và để họ chọn lại. Chỉ khi đã xác định intent cũ
  bị từ chối và người dùng chủ động tạo intent khác mới sinh key mới.
- IDEMPOTENCY_KEY_REUSED: lỗi intent/key hoặc actor đã đổi; không sửa ngầm key rồi gửi lại.
- 401 theo refresh/login flow; 403 cập nhật quyền/feature; 404 refresh workspace.
- UI guard chỉ hỗ trợ hiển thị; backend là quyết định cuối cùng dưới restaurant lock.

## Create/append retry sau transfer

Original create request dùng tableId A phải giữ nguyên tableId A và key khi retry,
kể cả session/order đó đã chuyển sang B. Backend trả lại order cũ và không mở session
mới ở A. Sau replay, resolve session/current seating để hiển thị B, không lấy A từ
request cũ làm vị trí hiện tại. Append replay cũng giữ nguyên frozen payload/key.

Không suy ra cancel order làm bàn trống: occupancy phụ thuộc session OPEN.
QR là của bàn vật lý, không sao chép/rotate khi chuyển. Nhân viên có thể transfer
nhưng không có ORDER_VIEW; không tự cấp quyền đọc đơn từ quyền chuyển bàn.

## History

GET `/api/v1/table-sessions/{sessionId}/transfers?page=0&size=20` cần TABLE_VIEW.
Hiển thị từ bàn → đến bàn, snapshot tên/mã, thời gian, reason, actor ID. Dùng thứ tự
server (applied session version giảm dần), không tự sort chỉ theo timestamp.
Session.tableId là bàn hiện tại/cuối cùng, không phải snapshot vị trí tạo mọi order.

## Checklist kiểm thử FE

- Bàn nguồn trống, đích hiển thị cùng phiên/đơn/tổng tiền sau thành công.
- Không rời màn chi tiết order; nút gọi thêm vẫn trỏ orderId cũ.
- Hai máy chọn cùng bàn: máy thua hiển thị conflict và reload, không gộp phiên.
- Timeout rồi retry không tạo history/audit trùng.
- A→B→C rồi replay A→B: UI hiển thị C.
- Replay phiên terminal không cho gọi thêm hoặc ép status OPEN.
- Phân biệt session/table/order version; không reset giỏ hoặc tạo đơn khác tùy ý.
- Login/refresh lại sau V20 để nhận TABLE_TRANSFER; DENY/thiếu feature ẩn chức năng.
