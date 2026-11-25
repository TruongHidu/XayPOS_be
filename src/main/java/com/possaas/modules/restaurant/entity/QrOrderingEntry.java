package com.possaas.modules.restaurant.entity;

/** The two guest QR ordering entry points a restaurant can switch on or off independently. */
public enum QrOrderingEntry {
    /** QR printed on a table: dine-in ordering. */
    TABLE,
    /** Restaurant-wide menu QR: takeaway ordering. */
    TAKEAWAY
}
