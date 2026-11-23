package com.minierp.sales;

import com.minierp.shared.Errors;

/**
 * The sales order lifecycle as a state machine.
 *
 * <pre>
 *          confirm (reserve stock)        shipment (part)               shipment (rest)
 *  DRAFT ─────────────────────► CONFIRMED ──────────────► PARTIALLY_SHIPPED ──────────────► SHIPPED
 *    │                              │   \__________________ shipment (all) _________________►  ▲
 *    │ cancel                       │ cancel (release)         │ close (release the rest)
 *    ▼                              ▼                          ▼
 *  CANCELLED                    CANCELLED                    CLOSED
 * </pre>
 *
 * Same style as {@code PurchaseOrderStatus}: exhaustive switch expressions, so the compiler
 * makes you decide every transition when a status or action is added.
 */
public enum SalesOrderStatus {
    DRAFT,
    CONFIRMED,
    PARTIALLY_SHIPPED,
    SHIPPED,
    CLOSED,
    CANCELLED;

    public SalesOrderStatus after(SalesOrderAction action) {
        SalesOrderStatus next = next(action);
        if (next == null) {
            throw new Errors.InvalidTransitionException("sales order", this, action);
        }
        return next;
    }

    public boolean allows(SalesOrderAction action) {
        return next(action) != null;
    }

    private SalesOrderStatus next(SalesOrderAction action) {
        return switch (this) {
            case DRAFT -> switch (action) {
                case CONFIRM -> CONFIRMED;
                case CANCEL -> CANCELLED;
                case CLOSE -> null;
            };
            case CONFIRMED -> switch (action) {
                case CANCEL -> CANCELLED;
                case CONFIRM, CLOSE -> null;
            };
            case PARTIALLY_SHIPPED -> switch (action) {
                case CLOSE -> CLOSED;
                case CONFIRM, CANCEL -> null;
            };
            case SHIPPED, CLOSED, CANCELLED -> null; // final
        };
    }

    /** Whether stock is reserved for this order's open quantities. */
    public boolean holdsReservations() {
        return switch (this) {
            case CONFIRMED, PARTIALLY_SHIPPED -> true;
            case DRAFT, SHIPPED, CLOSED, CANCELLED -> false;
        };
    }

    public boolean acceptsShipments() {
        return holdsReservations();
    }

    /** The status after a shipment; {@code complete} = every line is now fully shipped. */
    public SalesOrderStatus afterShipment(boolean complete) {
        if (!acceptsShipments()) {
            throw new Errors.InvalidTransitionException("sales order", this, "ship");
        }
        return complete ? SHIPPED : PARTIALLY_SHIPPED;
    }

    public boolean isEditable() {
        return this == DRAFT;
    }
}
