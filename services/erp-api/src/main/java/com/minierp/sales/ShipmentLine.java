package com.minierp.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

/**
 * Shipped quantity of one order line. Which bins it was picked from is recorded in the stock
 * ledger (one SHIPMENT movement per bin), not here.
 */
@Entity
@Immutable
@Table(name = "shipment_lines")
public class ShipmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "sales_order_id", nullable = false)
    private long salesOrderId;

    @Column(name = "sales_order_line_id", nullable = false)
    private long salesOrderLineId;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "warehouse_id", nullable = false)
    private long warehouseId;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantity;

    @Column(name = "base_quantity", nullable = false)
    private long baseQuantity;

    protected ShipmentLine() {
        // for JPA
    }

    ShipmentLine(Shipment shipment, long salesOrderLineId, long productId, BigDecimal quantity, long baseQuantity) {
        this.shipment = shipment;
        this.salesOrderId = shipment.getSalesOrderId();
        this.warehouseId = shipment.getWarehouseId();
        this.salesOrderLineId = salesOrderLineId;
        this.productId = productId;
        this.quantity = quantity;
        this.baseQuantity = baseQuantity;
    }

    public Long getId() {
        return id;
    }

    public long getSalesOrderLineId() {
        return salesOrderLineId;
    }

    public long getProductId() {
        return productId;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public long getBaseQuantity() {
        return baseQuantity;
    }
}
