package com.minierp.inventory;

/**
 * Why stock moved. Each type has a fixed shape, checked by {@link StockMovement} and again by
 * the database ({@code stock_movements_shape}):
 *
 * <pre>
 *  type          on hand   reserved   bin    caused by
 *  RECEIPT        +q         0        yes    a goods receipt line
 *  RESERVE         0        +q        no     a sales order line (confirmed)
 *  RELEASE         0        -q        no     a sales order line (cancelled / closed short)
 *  SHIPMENT       -q        -q        yes    a shipment line (consumes the reservation)
 *  TRANSFER_OUT   -q         0        yes    a transfer line, leaving the source warehouse
 *  TRANSFER_IN    +q         0        yes    a transfer line, arriving at the destination
 *  ADJUSTMENT     +/-q       0        yes    a stock adjustment (count correction, damage)
 * </pre>
 *
 * available = on hand - reserved, so RESERVE, TRANSFER_OUT and a negative ADJUSTMENT reduce
 * availability, and SHIPMENT doesn't (that stock was already promised).
 */
public enum MovementType {
    RECEIPT,
    RESERVE,
    RELEASE,
    SHIPMENT,
    TRANSFER_OUT,
    TRANSFER_IN,
    ADJUSTMENT;

    /** The {@code stock_movements} column that links a movement of this type to its source. */
    String sourceColumn() {
        return switch (this) {
            case RECEIPT -> "goods_receipt_line_id";
            case RESERVE, RELEASE -> "sales_order_line_id";
            case SHIPMENT -> "shipment_line_id";
            case TRANSFER_OUT -> "transfer_out_line_id";
            case TRANSFER_IN -> "transfer_in_line_id";
            case ADJUSTMENT -> "adjustment_id";
        };
    }

    /** Whether movements of this type happen in a bin (physical) or for the warehouse as a whole. */
    boolean needsBin() {
        return switch (this) {
            case RESERVE, RELEASE -> false;
            case RECEIPT, SHIPMENT, TRANSFER_OUT, TRANSFER_IN, ADJUSTMENT -> true;
        };
    }
}
