package com.minierp.reporting;

import java.time.Instant;
import java.util.List;

/**
 * The stock report's data, independent of format: the PDF and Excel renderers print the same
 * object, so the two exports can't disagree.
 *
 * @param asOf        {@code null} for current stock; otherwise the instant the stock was
 *                    reconstructed for, from the ledger alone
 * @param warehouse   the warehouse filter as shown in the header ("All warehouses" if none)
 */
public record StockReport(Instant generatedAt, Instant asOf, String warehouse, List<Row> rows) {

    /** Low = at or below the reorder point (the same rule the alerts use). */
    public enum Status { OK, LOW, NO_RULE }

    /** One product in one warehouse. Quantities in the product's base unit. */
    public record Row(String warehouseCode, String sku, String productName, String baseUom, long onHand, long reserved,
            long available, Long reorderPoint, Long reorderQuantity) {

        public Status status() {
            if (reorderPoint == null) {
                return Status.NO_RULE;
            }
            return available <= reorderPoint ? Status.LOW : Status.OK;
        }
    }

    public StockReport {
        rows = List.copyOf(rows);
    }

    public long lowCount() {
        return rows.stream().filter(r -> r.status() == Status.LOW).count();
    }
}
