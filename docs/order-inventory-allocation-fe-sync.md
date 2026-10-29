# Đồng bộ FE — nguyên liệu theo order

Không thêm màn hình/luồng gửi reserve, consume hoặc release. Giữ URL, request body,
version, Idempotency-Key và security của ORDER/KDS/QR hiện có.

- Sau SUBMIT/confirm/append/approve thành công, thay order bằng response và reload
  balance khi màn hình có hiển thị tồn. GET availability chỉ hỗ trợ hiển thị;
  transaction submit/approve quyết định cuối cùng và có thể trả 409.
- quantity là tồn vật lý; reservedQuantity là phần giữ chỗ; availableQuantity là
  phần còn bán được. Client không tự tính rồi gửi reservedQuantity lên server.
  Logical zero có cả ba lượng bằng 0 và configured=false. LowStock vẫn theo tồn
  vật lý/minQuantity; không đổi logic filter /low-stock.
- INVENTORY_DEFAULT_WAREHOUSE_REQUIRED: báo owner cần cấu hình kho. Với
  INSUFFICIENT_AVAILABLE_STOCK, reload dữ liệu, cho staff xử lý lựa chọn/nhập tồn
  rồi thử lại. Không coi lỗi là order đã gửi một phần.
- QR PENDING/review không giữ nguyên liệu. Approve thiếu tồn không tự reject;
  request tiếp tục PENDING. Sau bổ sung tồn, review lại và dùng version/hash mới
  theo quote/context guard, không tự thay hash hoặc giả APPROVED trên client.
- Start cooking và bulk mark-served có thể làm giảm physical/reserved. Mark-ready,
  phục vụ tiếp, payment và complete không trừ kho thêm. Cancel PENDING chỉ giải
  phóng reserved, không tăng physical; refund không hoàn nguyên nguyên liệu.
- Retry mạng giữ đúng intent/key/body/version theo contract sẵn có. Replay thành
  công không reserve/consume/release lần hai. Khi 409 concurrency, reload trước
  khi đưa ra mutation mới; không tự áp lại intent vào dòng vừa append.
- Allocation detail chỉ dành cho người có đủ ba quyền đọc và ba feature. Không
  chờ hoặc gọi endpoint này trong waiter/kitchen flow. Không đưa ingredient,
  recipe usage, allocation detail hoặc cost vào public QR/KDS/order thường.
- Ledger có hai nguồn. Với ORDER_ITEM/RECIPE_USE, document fields null; từ V38
  unitCost bằng frozen usage reference price nếu configured, gồm 0.00. Unknown/
  legacy vẫn null; hiển thị “Chưa có giá tham chiếu”, không chuyển thành 0.
  Header INCOMPLETE vẫn có thể có tracked usage đã biết giá. Không suy tổng giá
  vốn món/lãi thực từ ledger vì MANUAL và untracked không có RECIPE_USE.
- Đổi default warehouse/đổi bàn không thay kho giữ cho dòng cũ. Gọi thêm có thể
  dùng kho default mới. Tên/SKU/công thức mới không làm thay quantity đã chốt.

Request/response, lỗi và ví dụ Postman:
[order-inventory-allocation-api.md](order-inventory-allocation-api.md).

Summary giá vốn đọc bằng quyền riêng, không trả ingredient:
[order-reference-cost-snapshot-fe-sync.md](order-reference-cost-snapshot-fe-sync.md).
