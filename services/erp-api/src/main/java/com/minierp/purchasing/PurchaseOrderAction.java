package com.minierp.purchasing;

import com.minierp.shared.Role;
import java.util.Set;

/**
 * What a user can do to a purchase order (POST /api/purchase-orders/{id}/actions/{action}).
 * Receiving goods is not an action here: it's a goods receipt document of its own.
 */
public enum PurchaseOrderAction {
    SUBMIT,
    APPROVE,
    REJECT,
    CANCEL,
    CLOSE;

    /** Who may do it (any one of these roles). The status decides <em>whether</em> it can be done. */
    public Set<Role> allowedRoles() {
        return switch (this) {
            case SUBMIT, CANCEL -> Set.of(Role.PURCHASING);
            case APPROVE, REJECT -> Set.of(Role.MANAGER);
            case CLOSE -> Set.of(Role.PURCHASING, Role.MANAGER);
        };
    }

    /** Approving or rejecting your own order would defeat the point of an approval. */
    public boolean requiresDifferentPersonThanCreator() {
        return switch (this) {
            case APPROVE, REJECT -> true;
            case SUBMIT, CANCEL, CLOSE -> false;
        };
    }
}
