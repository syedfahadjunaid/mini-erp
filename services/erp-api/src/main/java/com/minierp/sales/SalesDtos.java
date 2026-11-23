package com.minierp.sales;

import com.minierp.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Request and response bodies of the sales API. */
public final class SalesDtos {

    private SalesDtos() {}

    public record CreateSalesOrderRequest(
            @NotNull Long customerId,
            @NotNull Long warehouseId,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {}

    public record UpdateSalesOrderRequest(
            @NotNull Long version,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {}

    /**
     * @param uom            unit of {@code quantity} and the price; the product's base unit when omitted
     * @param unitPriceMinor net price per {@code uom}, in minor units of the customer's currency
     */
    public record LineRequest(
            @NotNull Long productId,
            String uom,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @PositiveOrZero Long unitPriceMinor) {}

    public record ActionRequest(@Size(max = 500) String comment) {}

    /** @param openBaseQuantity ordered but not shipped; reserved in the ledger while the order is confirmed */
    public record LineResponse(long id, int lineNo, long productId, String uom, BigDecimal quantity, long baseQuantity,
            long shippedBaseQuantity, long openBaseQuantity, Money unitPrice, int taxRateBp, Money lineNet) {}

    public record HistoryResponse(String action, String fromStatus, String toStatus, String actor, Instant occurredAt,
            String comment) {}

    /**
     * @param allowedActions what the CALLER may do now (status + role)
     * @param canEdit        whether the caller may replace the lines (a DRAFT, and SALES)
     * @param canShip        whether the caller may ship (confirmed, and WAREHOUSE)
     */
    public record SalesOrderResponse(long id, String orderNumber, long customerId, long warehouseId, String currency,
            SalesOrderStatus status, String notes, String createdBy, Instant createdAt, String confirmedBy,
            Instant confirmedAt, long version, Money netTotal, List<LineResponse> lines,
            Set<SalesOrderAction> allowedActions, boolean canEdit, boolean canShip, List<HistoryResponse> history) {}

    // ---- shipments ----------------------------------------------------------------------------

    public record ShipmentRequest(@NotEmpty @Size(max = 200) List<@Valid ShipmentLineRequest> lines) {}

    /** @param quantity in the order line's unit */
    public record ShipmentLineRequest(@NotNull Long lineId, @NotNull @Positive BigDecimal quantity) {}

    /** Where the goods were picked from (one entry per bin). */
    public record Pick(long binId, long quantity) {}

    public record ShipmentLineResponse(long id, long salesOrderLineId, long productId, BigDecimal quantity, long baseQuantity,
            List<Pick> picks) {}

    public record ShipmentResponse(long id, String shipmentNumber, long salesOrderId, long warehouseId, String shippedBy,
            Instant shippedAt, List<ShipmentLineResponse> lines) {}
}
