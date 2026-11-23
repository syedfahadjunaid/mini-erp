package com.minierp.purchasing;

import com.minierp.shared.Errors;
import java.util.Locale;

/** Parses the {action} path segment ("approve") into a {@link PurchaseOrderAction}; 404 if unknown. */
final class PurchaseOrderActions {

    private PurchaseOrderActions() {}

    static PurchaseOrderAction parse(String segment) {
        try {
            return PurchaseOrderAction.valueOf(segment.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Errors.NotFoundException("Action", segment);
        }
    }
}
