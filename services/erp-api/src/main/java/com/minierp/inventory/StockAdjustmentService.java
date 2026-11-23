package com.minierp.inventory;

import com.minierp.catalog.CatalogService;
import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.Errors;
import com.minierp.shared.Quantities;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock corrections. A negative adjustment can't take away stock that is reserved for a
 * customer (the ledger trigger refuses it with "not enough available stock"): release or
 * cancel the reservation first, so the shortfall is a visible decision rather than an
 * accident.
 */
@Service
public class StockAdjustmentService {

    private final StockAdjustmentRepository adjustments;
    private final StockLedger ledger;
    private final WarehouseService warehouses;
    private final CatalogService catalog;
    private final BusinessCalendar calendar;

    StockAdjustmentService(StockAdjustmentRepository adjustments, StockLedger ledger, WarehouseService warehouses,
            CatalogService catalog, BusinessCalendar calendar) {
        this.adjustments = adjustments;
        this.ledger = ledger;
        this.warehouses = warehouses;
        this.catalog = catalog;
        this.calendar = calendar;
    }

    @Transactional
    public StockDtos.AdjustmentResponse adjust(StockDtos.AdjustmentRequest request, Actor actor) {
        warehouses.requireActiveWarehouse(request.warehouseId(), "warehouseId");
        warehouses.requireBin(request.warehouseId(), request.binId(), "binId");
        CatalogService.ProductInfo product = catalog.requireActiveProduct(request.productId(), "productId");

        if (request.quantity().signum() == 0) {
            throw Errors.BusinessRuleException.forField("invalid_quantity", "quantity", "An adjustment can't be zero.");
        }
        String uom = request.uom() == null ? product.baseUom() : request.uom();
        long factor = catalog.baseUnitsPerUom(product.id(), uom, "uom");
        long magnitude = Quantities.toBase(request.quantity().abs(), factor, "quantity");
        long delta = request.quantity().signum() < 0 ? -magnitude : magnitude;

        StockAdjustment adjustment = adjustments.save(new StockAdjustment(request.warehouseId(), request.binId(),
                product.id(), delta, request.reason(), actor.username(), calendar.now()));
        StockLedger.PostedMovement posted = ledger.post(StockMovement.adjustment(request.warehouseId(), request.binId(),
                product.id(), delta, adjustment.getId()), actor);
        return new StockDtos.AdjustmentResponse(adjustment.getId(), posted.id(), request.warehouseId(), request.binId(),
                product.id(), delta, posted.onHandAfter(), posted.reservedAfter());
    }

    /** Convenience for seeding and tests: an opening balance of {@code quantity} base units. */
    @Transactional
    public StockDtos.AdjustmentResponse openingBalance(long warehouseId, long binId, long productId, long quantity, Actor actor) {
        return adjust(new StockDtos.AdjustmentRequest(warehouseId, binId, productId, BigDecimal.valueOf(quantity), null,
                "Opening balance"), actor);
    }
}
