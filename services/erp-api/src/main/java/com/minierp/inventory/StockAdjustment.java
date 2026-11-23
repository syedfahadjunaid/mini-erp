package com.minierp.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * A stock correction (after a count, damage, loss). Insert-only: {@link Immutable} tells
 * Hibernate never to UPDATE it, and the database refuses UPDATE and DELETE anyway.
 */
@Entity
@Immutable
@Table(name = "stock_adjustments")
public class StockAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private long warehouseId;

    @Column(name = "bin_id", nullable = false)
    private long binId;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(name = "quantity_delta", nullable = false)
    private long quantityDelta;

    @Column(nullable = false)
    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockAdjustment() {
        // for JPA
    }

    public StockAdjustment(long warehouseId, long binId, long productId, long quantityDelta, String reason, String createdBy,
            Instant createdAt) {
        this.warehouseId = warehouseId;
        this.binId = binId;
        this.productId = productId;
        this.quantityDelta = quantityDelta;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getQuantityDelta() {
        return quantityDelta;
    }
}
