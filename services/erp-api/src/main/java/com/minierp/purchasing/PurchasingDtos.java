package com.minierp.purchasing;

import com.minierp.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Request and response bodies of the purchasing API. */
public final class PurchasingDtos {

    private PurchasingDtos() {}

    /** @param currency defaults to the supplier's currency */
    public record CreatePurchaseOrderRequest(
            @NotNull Long supplierId,
            @NotNull Long warehouseId,
            String currency,
            LocalDate expectedDate,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {}

    /** Replaces a DRAFT order's lines; {@code version} must match (optimistic locking). */
    public record UpdatePurchaseOrderRequest(
            @NotNull Long version,
            LocalDate expectedDate,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {}

    /**
     * @param uom            unit of {@code quantity} and of the price; the product's base unit when omitted
     * @param unitPriceMinor price per {@code uom} in minor units of the order currency (1250 = 12.50)
     */
    public record LineRequest(
            @NotNull Long productId,
            String uom,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @PositiveOrZero Long unitPriceMinor) {}

    public record ActionRequest(@Size(max = 500) String comment) {}

    public record LineResponse(long id, int lineNo, long productId, String uom, BigDecimal quantity, long baseQuantity,
            long receivedBaseQuantity, Money unitPrice, Money lineTotal) {}

    public record HistoryResponse(String action, String fromStatus, String toStatus, String actor, Instant occurredAt,
            String comment) {}

    /**
     * @param allowedActions what the CALLER may do now (status + roles + separation of duties)
     * @param canEdit        whether the caller may replace the lines (a DRAFT, and PURCHASING)
     * @param canReceive     whether the caller may book a goods receipt (approved, and WAREHOUSE)
     */
    public record PurchaseOrderResponse(long id, String orderNumber, long supplierId, long warehouseId, String currency,
            PurchaseOrderStatus status, LocalDate expectedDate, String notes, String createdBy, Instant createdAt,
            String approvedBy, Instant approvedAt, long version, Money total, List<LineResponse> lines,
            Set<PurchaseOrderAction> allowedActions, boolean canEdit, boolean canReceive, List<HistoryResponse> history) {}

    // ---- goods receipts -----------------------------------------------------------------------

    public record ReceiptRequest(@NotEmpty @Size(max = 200) List<@Valid ReceiptLineRequest> lines) {}

    /**
     * @param lineId   the purchase order line
     * @param quantity in the order line's unit (e.g. 4 BOX)
     * @param binId    where to put it; the warehouse's receiving bin when omitted
     */
    public record ReceiptLineRequest(@NotNull Long lineId, @NotNull @Positive BigDecimal quantity, Long binId) {}

    public record ReceiptLineResponse(long id, long purchaseOrderLineId, long productId, long binId, BigDecimal quantity,
            long baseQuantity) {}

    public record GoodsReceiptResponse(long id, String receiptNumber, long purchaseOrderId, long warehouseId,
            String receivedBy, Instant receivedAt, List<ReceiptLineResponse> lines, PurchaseOrderStatus orderStatus) {}
}
