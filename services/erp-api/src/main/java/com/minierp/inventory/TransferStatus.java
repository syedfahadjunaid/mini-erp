package com.minierp.inventory;

import com.minierp.shared.Errors;

/**
 * Lifecycle of an inter-warehouse transfer.
 *
 * <pre>
 *   (create: stock leaves the source bins)      receive (stock enters a destination bin)
 *  ───────────────────────────────────────► IN_TRANSIT ─────────────────────────────► RECEIVED
 * </pre>
 *
 * While IN_TRANSIT the goods are in neither warehouse's stock (they're on a truck); the
 * reconciliation report lists them separately.
 */
public enum TransferStatus {
    IN_TRANSIT,
    RECEIVED;

    public enum Action { RECEIVE }

    /** The status after {@code action}; a 409 if the move isn't allowed. Exhaustive switch: see PurchaseOrderStatus. */
    public TransferStatus after(Action action) {
        return switch (this) {
            case IN_TRANSIT -> switch (action) {
                case RECEIVE -> RECEIVED;
            };
            case RECEIVED -> throw new Errors.InvalidTransitionException("transfer", this, action);
        };
    }
}
