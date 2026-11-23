package com.minierp.replenishment;

import com.minierp.shared.Errors;

/**
 * Lifecycle of a low-stock alert.
 *
 * <pre>
 *   OPEN ──acknowledge──► ACKNOWLEDGED
 *     │                        │
 *     └── stock back above ────┴──► RESOLVED   (automatic, when available > reorder point)
 * </pre>
 */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED;

    public enum Action { ACKNOWLEDGE, RESOLVE }

    public AlertStatus after(Action action) {
        return switch (this) {
            case OPEN -> switch (action) {
                case ACKNOWLEDGE -> ACKNOWLEDGED;
                case RESOLVE -> RESOLVED;
            };
            case ACKNOWLEDGED -> switch (action) {
                case RESOLVE -> RESOLVED;
                case ACKNOWLEDGE -> throw new Errors.InvalidTransitionException("alert", this, action);
            };
            case RESOLVED -> throw new Errors.InvalidTransitionException("alert", this, action);
        };
    }
}
