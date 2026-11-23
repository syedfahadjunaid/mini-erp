package com.minierp.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/** Response and request bodies of the stock API. Quantities are in the product's base unit. */
public final class StockDtos {

    private StockDtos() {}

    /** On hand, reserved and available for a product in a warehouse. */
    public record StockLevelView(long warehouseId, String warehouseCode, long productId, String sku, String productName,
            String baseUom, long onHand, long reserved, long available, Instant updatedAt) {}

    public record BinStockView(long warehouseId, long binId, String binCode, long productId, String sku, long onHand) {}

    public record MovementView(long id, String type, long warehouseId, Long binId, long productId, String sku,
            long onHandDelta, long reservedDelta, long levelSeq, long onHandAfter, long reservedAfter,
            String sourceType, long sourceId, Instant occurredAt, String recordedBy) {}

    /**
     * A correction after a count, damage, loss... {@code quantity} is signed: negative removes stock.
     *
     * @param uom the unit of {@code quantity}; the product's base unit when omitted
     */
    public record AdjustmentRequest(
            @NotNull Long warehouseId,
            @NotNull Long binId,
            @NotNull Long productId,
            @NotNull BigDecimal quantity,
            String uom,
            @NotBlank @Size(max = 500) String reason) {}

    public record AdjustmentResponse(long adjustmentId, long movementId, long warehouseId, long binId, long productId,
            long quantityDelta, long onHandAfter, long reservedAfter) {}
}
