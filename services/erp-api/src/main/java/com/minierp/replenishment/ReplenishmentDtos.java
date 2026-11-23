package com.minierp.replenishment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/** Request and response bodies for reorder rules and low-stock alerts (quantities in base units). */
public final class ReplenishmentDtos {

    private ReplenishmentDtos() {}

    /**
     * @param reorderPoint    alert when available stock is at or below this
     * @param reorderQuantity how much to order when it is (shown on the alert)
     */
    public record ReorderRuleRequest(@NotNull @PositiveOrZero Long reorderPoint, @NotNull @Positive Long reorderQuantity) {}

    public record ReorderRuleResponse(long warehouseId, String warehouseCode, long productId, String sku, long reorderPoint,
            long reorderQuantity, long available, String updatedBy, Instant updatedAt) {}

    public record AlertResponse(long id, long warehouseId, String warehouseCode, long productId, String sku, String productName,
            AlertStatus status, long reorderPoint, long reorderQuantity, long availableAtOpen, long availableNow,
            Instant raisedAt, String acknowledgedBy, Instant acknowledgedAt, Instant resolvedAt) {}
}
