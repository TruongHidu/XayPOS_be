# Đồng bộ FE — cảnh báo tồn thấp

Không cần sửa luồng gửi món hoặc gọi API reserve/consume bổ sung.
Backend duy trì cảnh báo cùng transaction nguồn. Chỉ triển khai màn cảnh báo cho
người có INVENTORY_VIEW, INVENTORY_MANAGEMENT và LOW_STOCK_ALERT. Nút “Đã xem” cần
thêm INVENTORY_ALERT_ACKNOWLEDGE. Backend luôn là nơi quyết định cuối cùng.

## API và UI

- GET `/api/v1/inventory/alerts`: paging/filter theo [API](low-stock-alert-api.md).
- GET `/api/v1/inventory/alerts/{alertId}`: chi tiết/lịch sử.
- POST `/api/v1/inventory/alerts/{alertId}/acknowledge`, body expectedVersion hiện có.

OPEN hiển thị chưa xem; ACKNOWLEDGED đã xem nhưng vẫn có thể thiếu; RESOLVED đã kết
thúc đợt cảnh báo. Hiển thị resolutionReason để phân biệt nhập đủ với tắt threshold,
disable/delete nguồn hoặc đổi đơn vị. Không dùng chữ “Đã nhập kho” cho acknowledge.

Không truyền status thì list gồm mọi trạng thái; có thể làm tab theo từng status.
Không dùng totalElements của trang OPEN để khẳng định tổng số nguyên liệu đang
thiếu, vì còn ACKNOWLEDGED. Đọc `/inventory/low-stock` khi cần danh sách số dư hiện
đang thấp, không dùng historical alerts thay thế tồn hiện tại.

quantityAtTrigger/thresholdQuantity/name/SKU/unit là snapshot. Các số hiện tại được
đặt tên riêng; null thì hiển thị “Không còn dữ liệu so sánh”, không đổi thành 0.
Current quantity thay đổi mà alert version không đổi là bình thường. Đổi tên nguồn
không làm metadata snapshot đổi. Với đổi baseUnit, owner phải rà soát threshold:
backend không tự quy đổi giá trị số.

409 stale version: reload detail trước retry. ACK desired-state replay không tạo
audit trùng; RESOLVED không ACK được. 403 feature/permission: không cố gọi API bằng
cách chỉ ẩn thông báo lỗi. Không cache/chia sẻ dữ liệu giữa các tenant/user.

Backend đã hỗ trợ native WebSocket/STOMP `/ws`, xác thực bằng Bearer trong CONNECT,
subscribe `/user/queue/inventory-alerts`; xem [contract realtime](inventory-alert-realtime.md).
Sự kiện `INVENTORY_LOW_STOCK_ALERT_CHANGED` chỉ gửi sau commit và trong đúng tenant.
OPEN mới có thể hiện thông báo trong app; ACKNOWLEDGED/RESOLVED chỉ đồng bộ lại UI.
Vẫn dùng REST để đọc danh sách/chi tiết và tải lại sau kết nối lại vì không replay.
Dừng socket khi tắt cập nhật, background, logout hoặc mất quyền. Không cần polling
định kỳ để nhận sự kiện. Không tự tạo/resolve cảnh báo phía frontend.
