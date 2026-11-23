package com.minierp.purchasing;

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
 * One product on a purchase order: what the buyer typed (quantity + unit, price per unit) and
 * the same quantity in base units, which is what receipts count against.
 */
@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder order;

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

    @Column(name = "received_base_quantity", nullable = false)
    private long receivedBaseQuantity;

    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    private long unitPriceMinor;

    @Column(name = "line_total_minor", nullable = false, updatable = false)
    private long lineTotalMinor;

    protected PurchaseOrderLine() {
        // for JPA
    }

    PurchaseOrderLine(PurchaseOrder order, int lineNo, long productId, String uom, BigDecimal quantity, long baseQuantity,
            long unitPriceMinor, long lineTotalMinor) {
        this.order = order;
        this.lineNo = lineNo;
        this.productId = productId;
        this.uom = uom;
        this.quantity = quantity;
        this.baseQuantity = baseQuantity;
        this.unitPriceMinor = unitPriceMinor;
        this.lineTotalMinor = lineTotalMinor;
    }

    /** Books {@code base} more units as received (the caller checked it fits). */
    void receive(long base) {
        if (base <= 0 || base > remainingBaseQuantity()) {
            throw new IllegalArgumentException("Can't receive " + base + " of line " + lineNo);
        }
        receivedBaseQuantity += base;
    }

    public long remainingBaseQuantity() {
        return baseQuantity - receivedBaseQuantity;
    }

    public boolean isFullyReceived() {
        return receivedBaseQuantity == baseQuantity;
    }

    public Long getId() {
        return id;
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

    public long getReceivedBaseQuantity() {
        return receivedBaseQuantity;
    }

    public long getUnitPriceMinor() {
        return unitPriceMinor;
    }

    public long getLineTotalMinor() {
        return lineTotalMinor;
    }
}
