package com.possaas.modules.qrorder.application.port;

import com.possaas.modules.payment.application.port.PaymentGuestRequestLifecycle;
import com.possaas.modules.restaurant.application.port.RestaurantMenuGuestAccessLifecycle;
import com.possaas.modules.table.application.port.TableGuestAccessLifecycle;

/** Synchronous QR invalidation hooks; callers hold the shared restaurant lock and transaction. */
public interface QrRequestLifecycle extends TableGuestAccessLifecycle,
        RestaurantMenuGuestAccessLifecycle, PaymentGuestRequestLifecycle {}
