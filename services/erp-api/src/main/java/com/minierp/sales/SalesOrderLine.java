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

/**
 * One product on a sales order. While the order is CONFIRMED or PARTIALLY_SHIPPED, exactly
 * {@link #openBaseQuantity()} units are reserved for this line in the stock ledger (the
 * reconciliation report checks that).
 */
@Entity
@Table(name = "sales_order_lines")
public class SalesOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_order_id", nullable = false, updatable = false)
    private SalesOrder order;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private long warehouseId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;

    @Column(name = "uom_code", nullable = false, updatable = false)
    private String uom;

    @Column(nullable = false, updatable = false, precision = 18, scale = 3)
    private BigDecimal quantity;

    @Column(name = "base_quantity", nullable = false, updatable = false)
    private long baseQuantity;

    @Column(name = "shipped_base_quantity", nullable = false)
    private long shippedBaseQuantity;

    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    private long unitPriceMinor;

    @Column(name = "tax_rate_bp", nullable = false, updatable = false)
    private int taxRateBp;

    @Column(name = "line_net_minor", nullable = false, updatable = false)
    private long lineNetMinor;

    protected SalesOrderLine() {
        // for JPA
    }

    SalesOrderLine(SalesOrder order, int lineNo, long productId, String uom, BigDecimal quantity, long baseQuantity,
            long unitPriceMinor, int taxRateBp, long lineNetMinor) {
        this.order = order;
        this.warehouseId = order.getWarehouseId();
        this.lineNo = lineNo;
        this.productId = productId;
        this.uom = uom;
        this.quantity = quantity;
        this.baseQuantity = baseQuantity;
        this.unitPriceMinor = unitPriceMinor;
        this.taxRateBp = taxRateBp;
        this.lineNetMinor = lineNetMinor;
    }

    void ship(long base) {
        if (base <= 0 || base > openBaseQuantity()) {
            throw new IllegalArgumentException("Can't ship " + base + " of line " + lineNo);
        }
        shippedBaseQuantity += base;
    }

    /** Ordered but not shipped yet (in base units). */
    public long openBaseQuantity() {
        return baseQuantity - shippedBaseQuantity;
    }

    public boolean isFullyShipped() {
        return shippedBaseQuantity == baseQuantity;
    }

    public Long getId() {
        return id;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public long getProductId() {
        return productId;
    }

    public String getUom() {
        return uom;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public long getBaseQuantity() {
        return baseQuantity;
    }

    public long getShippedBaseQuantity() {
        return shippedBaseQuantity;
    }

    public long getUnitPriceMinor() {
        return unitPriceMinor;
    }

    public int getTaxRateBp() {
        return taxRateBp;
    }

    public long getLineNetMinor() {
        return lineNetMinor;
    }
}
