package com.minierp.purchasing;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.Immutable;

/** A delivery booked into a warehouse against a purchase order. Written once, never changed. */
@Entity
@Immutable
@Table(name = "goods_receipts")
public class GoodsReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_number", nullable = false)
    private String receiptNumber;

    @Column(name = "purchase_order_id", nullable = false)
    private long purchaseOrderId;

    @Column(name = "warehouse_id", nullable = false)
    private long warehouseId;

    @Column(name = "received_by", nullable = false)
    private String receivedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<GoodsReceiptLine> lines = new ArrayList<>();

    protected GoodsReceipt() {
        // for JPA
    }

    GoodsReceipt(String receiptNumber, long purchaseOrderId, long warehouseId, String receivedBy, Instant receivedAt) {
        this.receiptNumber = receiptNumber;
        this.purchaseOrderId = purchaseOrderId;
        this.warehouseId = warehouseId;
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
    }

    GoodsReceiptLine addLine(PurchaseOrderLine orderLine, long binId, BigDecimal quantity, long baseQuantity) {
        GoodsReceiptLine line = new GoodsReceiptLine(this, orderLine.getId(), orderLine.getProductId(), binId, quantity,
                baseQuantity);
        lines.add(line);
        return line;
    }

    public Long getId() {
        return id;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public List<GoodsReceiptLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
