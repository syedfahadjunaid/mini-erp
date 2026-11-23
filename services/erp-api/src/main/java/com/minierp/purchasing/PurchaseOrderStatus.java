package com.minierp.purchasing;

import com.minierp.shared.Errors;

/**
 * The purchase order lifecycle as a state machine.
 *
 * <pre>
 *            submit             approve              receipt (part)            receipt (rest)
 *   DRAFT ─────────► SUBMITTED ─────────► APPROVED ───────────► PARTIALLY_RECEIVED ──────────► RECEIVED
 *     ▲                  │                   │    \______________ receipt (all) ______________►  ▲
 *     └──── reject ──────┘                   │                         │ close (no more coming)
 *                                            │                         ▼
 *   cancel: from DRAFT, SUBMITTED or APPROVED (nothing received yet)   CLOSED
 *     ──► CANCELLED
 * </pre>
 *
 * Every rule about which move is legal lives in this enum. The methods are switch
 * <em>expressions</em> over enums with no {@code default} branch, so the compiler checks that
 * every status and every action is handled: add a status and the code won't compile until each
 * transition into and out of it has been decided. Who may make a move is decided by
 * {@link PurchaseOrderAction#allowedRoles()} and the service.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED;

    /** The status after {@code action}, or a 409 if this status doesn't allow it. */
    public PurchaseOrderStatus after(PurchaseOrderAction action) {
        PurchaseOrderStatus next = next(action);
        if (next == null) {
            throw new Errors.InvalidTransitionException("purchase order", this, action);
        }
        return next;
    }

    public boolean allows(PurchaseOrderAction action) {
        return next(action) != null;
    }

    /** {@code null} means "not allowed". */
    private PurchaseOrderStatus next(PurchaseOrderAction action) {
        return switch (this) {
            case DRAFT -> switch (action) {
                case SUBMIT -> SUBMITTED;
                case CANCEL -> CANCELLED;
                case APPROVE, REJECT, CLOSE -> null;
            };
            case SUBMITTED -> switch (action) {
                case APPROVE -> APPROVED;
                case REJECT -> DRAFT; // back to the buyer to fix
                case CANCEL -> CANCELLED;
                case SUBMIT, CLOSE -> null;
            };
            case APPROVED -> switch (action) {
                case CANCEL -> CANCELLED; // nothing received yet, so nothing to undo
                case SUBMIT, APPROVE, REJECT, CLOSE -> null;
            };
            case PARTIALLY_RECEIVED -> switch (action) {
                case CLOSE -> CLOSED; // the rest won't come: stop expecting it
                case SUBMIT, APPROVE, REJECT, CANCEL -> null;
            };
            case RECEIVED, CLOSED, CANCELLED -> null; // final
        };
    }

    /** Whether goods may be received against an order in this status. */
    public boolean acceptsReceipts() {
        return switch (this) {
            case APPROVED, PARTIALLY_RECEIVED -> true;
            case DRAFT, SUBMITTED, RECEIVED, CLOSED, CANCELLED -> false;
        };
    }

    /** The status after a goods receipt; {@code complete} = every line is now fully received. */
    public PurchaseOrderStatus afterReceipt(boolean complete) {
        if (!acceptsReceipts()) {
            throw new Errors.InvalidTransitionException("purchase order", this, "receive goods");
        }
        return complete ? RECEIVED : PARTIALLY_RECEIVED;
    }

    /** Lines can only be changed while the order is a draft. */
    public boolean isEditable() {
        return this == DRAFT;
    }
}
