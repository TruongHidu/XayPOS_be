-- Vietnamese display names and descriptions for the permission catalog shown to restaurant owners.
-- Descriptions state the scope of each permission and any other permission it must be combined with.
UPDATE permissions p
SET name = v.name, description = v.description, updated_at = now()
FROM (VALUES
    -- MENU
    ('MENU_VIEW', 'Xem thực đơn', 'Xem danh sách nhóm món và món ăn.'),
    ('MENU_CREATE', 'Thêm món và nhóm món', 'Tạo món ăn và nhóm món mới.'),
    ('MENU_UPDATE', 'Sửa món và nhóm món', 'Sửa thông tin, giá, trạng thái hoạt động và nhóm của món; sửa nhóm món.'),
    ('MENU_DELETE', 'Xoá món và nhóm món', 'Xoá món ăn hoặc nhóm món khỏi thực đơn.'),
    ('MENU_AVAILABILITY_UPDATE', 'Bật/tắt món còn hàng', 'Đánh dấu món tạm hết hoặc có bán trở lại trong ca, không được sửa thông tin món.'),
    ('RECIPE_VIEW', 'Xem công thức', 'Xem công thức định lượng nguyên liệu và giá vốn ước tính của món.'),
    ('RECIPE_CREATE', 'Tạo công thức', 'Tạo công thức đầu tiên cho món.'),
    ('RECIPE_UPDATE', 'Cập nhật công thức', 'Tạo phiên bản công thức mới thay cho phiên bản đang dùng.'),
    ('RECIPE_DELETE', 'Ngừng dùng công thức', 'Ngừng áp dụng công thức; lịch sử đơn đã bán vẫn được giữ.'),
    -- TABLE
    ('TABLE_VIEW', 'Xem bàn và khu vực', 'Xem sơ đồ bàn, khu vực và phiên bàn đang mở.'),
    ('TABLE_CREATE', 'Thêm bàn và khu vực', 'Tạo bàn và khu vực mới.'),
    ('TABLE_UPDATE', 'Sửa bàn và khu vực', 'Sửa, đổi khu vực, bật/tắt, xoá bàn và khu vực; tạo lại mã QR của bàn.'),
    ('TABLE_OPEN', 'Mở bàn', 'Mở phiên phục vụ mới cho bàn trống và cập nhật phiên đang mở.'),
    ('TABLE_CLOSE', 'Đóng và huỷ phiên bàn', 'Đóng hoặc huỷ phiên phục vụ của bàn.'),
    ('TABLE_TRANSFER', 'Chuyển bàn', 'Chuyển phiên đang mở sang một bàn trống khác.'),
    -- ORDER
    ('ORDER_VIEW', 'Xem đơn hàng', 'Xem danh sách, chi tiết đơn và yêu cầu gọi món qua QR. Cần cho hầu hết các quyền thao tác đơn khác.'),
    ('ORDER_CREATE', 'Tạo đơn hàng', 'Tạo đơn mới. Duyệt yêu cầu gọi món qua QR cần thêm quyền Sửa đơn hàng.'),
    ('ORDER_UPDATE', 'Sửa đơn hàng', 'Thêm, sửa, huỷ món trong đơn, gửi đơn xuống bếp và từ chối yêu cầu gọi món qua QR.'),
    ('ORDER_CANCEL', 'Huỷ đơn hàng', 'Huỷ cả đơn; cũng được huỷ từng món trong đơn.'),
    ('ORDER_MARK_SERVED', 'Đánh dấu đã phục vụ', 'Xác nhận món hoặc cả đơn đã mang ra cho khách.'),
    ('ORDER_COMPLETE', 'Hoàn tất đơn', 'Hoàn tất đơn đã thanh toán và đã phục vụ. Với đơn tại bàn sẽ đóng luôn phiên bàn nên cần thêm quyền Đóng phiên bàn.'),
    -- KITCHEN
    ('KITCHEN_VIEW', 'Xem màn hình bếp', 'Xem hàng chờ món cần chế biến.'),
    ('KITCHEN_START_COOKING', 'Bắt đầu chế biến', 'Chuyển món sang trạng thái đang nấu.'),
    ('KITCHEN_MARK_READY', 'Báo món đã xong', 'Đánh dấu món hoặc cả đơn đã nấu xong, chờ mang ra.'),
    -- PAYMENT
    ('PAYMENT_VIEW', 'Xem thanh toán', 'Xem các lần thanh toán của đơn.'),
    ('PAYMENT_CREATE', 'Tạo thanh toán', 'Tạo yêu cầu thanh toán cho đơn.'),
    ('PAYMENT_CONFIRM', 'Xác nhận đã thu tiền', 'Xác nhận khách đã trả tiền cho một lần thanh toán.'),
    ('PAYMENT_REFUND', 'Hoàn tiền', 'Chưa được sử dụng.'),
    -- STAFF
    ('STAFF_VIEW', 'Xem nhân viên', 'Xem danh sách nhân viên và vai trò.'),
    ('STAFF_CREATE', 'Thêm nhân viên', 'Tạo tài khoản nhân viên mới trong giới hạn của gói.'),
    ('STAFF_UPDATE', 'Sửa nhân viên', 'Sửa thông tin, email và vai trò của nhân viên.'),
    ('STAFF_DISABLE', 'Khoá/mở khoá nhân viên', 'Vô hiệu hoá hoặc kích hoạt lại tài khoản nhân viên.'),
    ('STAFF_PERMISSION_MANAGE', 'Phân quyền nhân viên', 'Cấp hoặc chặn quyền riêng cho từng nhân viên. Chỉ cấp được những quyền mà bản thân đang có.'),
    -- INVENTORY
    ('INGREDIENT_VIEW', 'Xem nguyên liệu', 'Xem danh mục nguyên liệu.'),
    ('INGREDIENT_CREATE', 'Thêm nguyên liệu', 'Tạo nguyên liệu mới.'),
    ('INGREDIENT_UPDATE', 'Sửa nguyên liệu', 'Sửa thông tin và bật/tắt nguyên liệu.'),
    ('INGREDIENT_DELETE', 'Xoá nguyên liệu', 'Xoá nguyên liệu khỏi danh mục.'),
    ('WAREHOUSE_CREATE', 'Thêm kho', 'Tạo kho mới. Cần thêm quyền Xem tồn kho.'),
    ('WAREHOUSE_UPDATE', 'Sửa kho', 'Sửa thông tin, bật/tắt và đặt kho mặc định. Cần thêm quyền Xem tồn kho.'),
    ('WAREHOUSE_DELETE', 'Xoá kho', 'Xoá kho. Cần thêm quyền Xem tồn kho.'),
    ('INVENTORY_VIEW', 'Xem tồn kho', 'Xem kho, tồn kho, phiếu kho, lịch sử nhập xuất, cảnh báo và phiếu kiểm kho. Cần cho mọi quyền kho khác.'),
    ('INVENTORY_RECEIVE', 'Nhập kho', 'Lập, sửa, xác nhận và huỷ phiếu nhập kho.'),
    ('INVENTORY_ISSUE', 'Xuất kho', 'Lập, sửa, xác nhận và huỷ phiếu xuất kho thủ công (hư hỏng, dùng nội bộ...).'),
    ('INVENTORY_ADJUST', 'Điều chỉnh tồn kho', 'Lập, sửa, xác nhận và huỷ phiếu điều chỉnh số lượng tồn.'),
    ('INVENTORY_TRANSFER', 'Chuyển kho', 'Chưa được sử dụng.'),
    ('INVENTORY_THRESHOLD_UPDATE', 'Đặt ngưỡng tồn tối thiểu', 'Đặt mức tồn tối thiểu để cảnh báo sắp hết hàng.'),
    ('INVENTORY_ALERT_ACKNOWLEDGE', 'Xử lý cảnh báo hết hàng', 'Đánh dấu đã xem cảnh báo sắp hết hàng. Cần thêm quyền Xem tồn kho.'),
    ('STOCK_AUDIT_MANAGE', 'Lập phiếu kiểm kho', 'Tạo, sửa, bắt đầu đếm, nhập số đếm và huỷ phiếu kiểm kho. Cần thêm quyền Xem tồn kho.'),
    ('STOCK_AUDIT_CONFIRM', 'Chốt kiểm kho', 'Chốt phiếu kiểm kho và cập nhật tồn theo số đếm thực tế. Cần thêm quyền Xem tồn kho.'),
    -- REPORT / AI
    ('REPORT_DAILY_VIEW', 'Xem báo cáo trong ngày', 'Xem doanh thu và số đơn trong ngày.'),
    ('REPORT_DETAIL_VIEW', 'Xem báo cáo chi tiết', 'Xem tổng quan, doanh thu theo thời gian, phương thức thanh toán, món bán chạy. Xem lãi gộp và giá vốn cần thêm quyền Xem đơn hàng.'),
    ('ANALYTICS_VIEW', 'Xem phân tích nâng cao', 'Chưa được sử dụng.'),
    ('AI_FORECAST_VIEW', 'Xem dự báo AI', 'Chưa được sử dụng.'),
    -- CASHBOOK
    ('CASHBOOK_VIEW', 'Xem sổ thu chi', 'Xem thu chi, tổng hợp và biểu đồ dòng tiền. Xem chi phí từ phiếu nhập kho cần thêm quyền Xem tồn kho.'),
    ('CASHBOOK_CREATE', 'Ghi khoản chi', 'Ghi nhận khoản chi đã trả. Gắn với phiếu nhập kho cần thêm quyền Xem tồn kho.'),
    ('CASHBOOK_VOID', 'Huỷ khoản chi', 'Huỷ khoản chi ghi nhầm; bản ghi vẫn được giữ lại để đối chiếu.'),
    -- RESTAURANT PROFILE (owner only, not assignable to staff)
    ('RESTAURANT_PROFILE_VIEW', 'Xem thông tin nhà hàng', 'Xem hồ sơ nhà hàng của mình.'),
    ('RESTAURANT_PROFILE_UPDATE', 'Sửa thông tin nhà hàng', 'Sửa hồ sơ nhà hàng và tạo lại link thực đơn công khai.'),
    -- SUBSCRIPTION (owner / super admin)
    ('SUBSCRIPTION_VIEW', 'Xem gói đang dùng', 'Xem gói hiện tại và lịch sử gói. Với super admin: xem gói của mọi nhà hàng.'),
    ('SUBSCRIPTION_MANAGE', 'Quản lý gói của nhà hàng', 'Super admin: gán, kích hoạt, đổi và huỷ gói cho nhà hàng.'),
    ('SUBSCRIPTION_PURCHASE_VIEW', 'Xem đơn mua gói', 'Xem các đơn mua gói của nhà hàng mình.'),
    ('SUBSCRIPTION_PURCHASE_CREATE', 'Mua gói', 'Tạo đơn mua hoặc gia hạn gói và tạo lượt thanh toán.'),
    ('SUBSCRIPTION_PURCHASE_CANCEL', 'Huỷ đơn mua gói', 'Huỷ đơn mua gói chưa thanh toán.'),
    ('SUBSCRIPTION_PAYMENT_REVIEW', 'Duyệt thanh toán gói', 'Super admin: xử lý các khoản thanh toán gói cần kiểm tra thủ công.'),
    -- ADMIN (super admin only)
    ('ADMIN_DASHBOARD_VIEW', 'Xem bảng điều khiển hệ thống', 'Super admin: xem số liệu tổng quan toàn hệ thống.'),
    ('RESTAURANT_VIEW', 'Xem nhà hàng', 'Super admin: xem danh sách, chi tiết và người dùng của các nhà hàng.'),
    ('RESTAURANT_MANAGE', 'Quản lý trạng thái nhà hàng', 'Super admin: khoá hoặc mở khoá nhà hàng.'),
    ('AUDIT_VIEW', 'Xem nhật ký hệ thống', 'Super admin: xem nhật ký thao tác.'),
    ('PACKAGE_VIEW', 'Xem gói và tính năng', 'Super admin: xem danh mục gói và tính năng.'),
    ('PACKAGE_MANAGE', 'Quản lý gói và tính năng', 'Super admin: tạo, sửa gói, tính năng và gán tính năng vào gói.')
) AS v(code, name, description)
WHERE p.code = v.code;

-- No endpoint checks these permissions yet; hide them from the staff catalog and effective permission sets.
UPDATE permissions
SET is_active = false, updated_at = now()
WHERE code IN ('PAYMENT_REFUND', 'INVENTORY_TRANSFER', 'ANALYTICS_VIEW', 'AI_FORECAST_VIEW');
