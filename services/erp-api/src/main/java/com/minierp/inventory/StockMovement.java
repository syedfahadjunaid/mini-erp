package com.minierp.inventory;

/**
 * One movement to post to the ledger. Build it with the factory methods, which take positive
 * quantities and choose the signs, so a caller can't, say, receive a negative amount.
 *
 * @param binId    the bin for physical movements; {@code null} for reservations
 * @param sourceId id of the document line that caused it (which table depends on the type)
 */
public record StockMovement(MovementType type, long warehouseId, Long binId, long productId,
        long onHandDelta, long reservedDelta, long sourceId) {

    public StockMovement {
        if ((binId != null) != type.needsBin()) {
            throw new IllegalArgumentException(type + (type.needsBin() ? " needs a bin" : " must not have a bin"));
        }
        boolean valid = switch (type) {
            case RECEIPT, TRANSFER_IN -> onHandDelta > 0 && reservedDelta == 0;
            case RESERVE -> onHandDelta == 0 && reservedDelta > 0;
            case RELEASE -> onHandDelta == 0 && reservedDelta < 0;
            case SHIPMENT -> onHandDelta < 0 && reservedDelta == onHandDelta;
            case TRANSFER_OUT -> onHandDelta < 0 && reservedDelta == 0;
            case ADJUSTMENT -> onHandDelta != 0 && reservedDelta == 0;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid " + type + ": on hand " + onHandDelta + ", reserved " + reservedDelta);
        }
    }

    public static StockMovement receipt(long warehouseId, long binId, long productId, long quantity, long goodsReceiptLineId) {
        return new StockMovement(MovementType.RECEIPT, warehouseId, binId, productId, quantity, 0, goodsReceiptLineId);
    }

    public static StockMovement reserve(long warehouseId, long productId, long quantity, long salesOrderLineId) {
        return new StockMovement(MovementType.RESERVE, warehouseId, null, productId, 0, quantity, salesOrderLineId);
    }

    public static StockMovement release(long warehouseId, long productId, long quantity, long salesOrderLineId) {
        return new StockMovement(MovementType.RELEASE, warehouseId, null, productId, 0, -quantity, salesOrderLineId);
    }

    public static StockMovement shipment(long warehouseId, long binId, long productId, long quantity, long shipmentLineId) {
        return new StockMovement(MovementType.SHIPMENT, warehouseId, binId, productId, -quantity, -quantity, shipmentLineId);
    }

    public static StockMovement transferOut(long warehouseId, long binId, long productId, long quantity, long transferLineId) {
        return new StockMovement(MovementType.TRANSFER_OUT, warehouseId, binId, productId, -quantity, 0, transferLineId);
    }

    public static StockMovement transferIn(long warehouseId, long binId, long productId, long quantity, long transferLineId) {
        return new StockMovement(MovementType.TRANSFER_IN, warehouseId, binId, productId, quantity, 0, transferLineId);
    }

    /** @param delta positive to add stock, negative to remove it */
    public static StockMovement adjustment(long warehouseId, long binId, long productId, long delta, long adjustmentId) {
        return new StockMovement(MovementType.ADJUSTMENT, warehouseId, binId, productId, delta, 0, adjustmentId);
    }
}
