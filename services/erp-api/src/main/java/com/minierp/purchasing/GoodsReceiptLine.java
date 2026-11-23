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
import org.hibernate.annotations.Immutable;

/**
 * Received quantity of one order line, put into one bin. {@code purchase_order_id} and
 * {@code warehouse_id} repeat the header's values; they're written from the header so the
 * composite foreign keys can check them.
 */
@Entity
@Immutable
@Table(name = "goods_receipt_lines")
public class GoodsReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    private GoodsReceipt receipt;

    @Column(name = "purchase_order_id", nullable = false)
    private long purchaseOrderId;

    @Column(name = "purchase_order_line_id", nullable = false)
    private long purchaseOrderLineId;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "warehouse_id", nullable = false)
    private long warehouseId;

    @Column(name = "bin_id", nullable = false)
    private long binId;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantity;

    @Column(name = "base_quantity", nullable = false)
    private long baseQuantity;

    protected GoodsReceiptLine() {
        // for JPA
    }

    GoodsReceiptLine(GoodsReceipt receipt, long purchaseOrderLineId, long productId, long binId, BigDecimal quantity,
            long baseQuantity) {
        this.receipt = receipt;
        this.purchaseOrderId = receipt.getPurchaseOrderId();
        this.warehouseId = receipt.getWarehouseId();
        this.purchaseOrderLineId = purchaseOrderLineId;
        this.productId = productId;
        this.binId = binId;
        this.quantity = quantity;
        this.baseQuantity = baseQuantity;
    }

    public Long getId() {
        return id;
    }

    public long getPurchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public long getProductId() {
        return productId;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public long getBinId() {
        return binId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public long getBaseQuantity() {
        return baseQuantity;
    }
}
