package com.minierp.shared;

/**
 * The realm roles in Keycloak (realm {@code erp}). Each matches a job, so the API can keep
 * duties apart: the person who raises a purchase order isn't the one who approves it, and the
 * warehouse books goods in and out but doesn't set prices.
 *
 * <p>Checked with {@code @PreAuthorize("hasRole('PURCHASING')")} on controller methods.
 * Relationship rules ("not your own order") live in the services.
 */
public enum Role {
    /** Master data: products, warehouses and bins, suppliers, customers. */
    ADMIN,
    /** Raises, edits, submits and cancels purchase orders. */
    PURCHASING,
    /** Approves or rejects purchase orders (not their own), adjusts stock, sets reorder points. */
    MANAGER,
    /** Receives goods, ships orders, moves stock between warehouses. */
    WAREHOUSE,
    /** Takes, confirms and cancels sales orders. */
    SALES,
    /** Issues invoices and runs financial reports. */
    FINANCE;

    /** Spring Security's naming convention: {@code hasRole('X')} checks the authority "ROLE_X". */
    public String authority() {
        return "ROLE_" + name();
    }
}
