package com.minierp.sales;

/**
 * What a user can do to a sales order (POST /api/sales-orders/{id}/actions/{action}); all of
 * them need the SALES role. Shipping is a document of its own (a shipment), done by WAREHOUSE.
 */
public enum SalesOrderAction {
    /** Reserve the stock for every line, all or nothing. */
    CONFIRM,
    /** Before anything shipped; releases the reservations. */
    CANCEL,
    /** After a partial shipment, when the rest won't be shipped; releases what's still reserved. */
    CLOSE
}
