package com.minierp.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One product on a transfer, in base units. The warehouse columns repeat the header's on
 * purpose: they are part of composite foreign keys that tie the bins to the right warehouses.
 */
@Entity
@Table(name = "stock_transfer_lines")
public class StockTransferLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false, updatable = false)
    private StockTransfer transfer;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;

    @Column(nullable = false, updatable = false)
    private long quantity;

    @Column(name = "from_warehouse_id", nullable = false, updatable = false)
    private long fromWarehouseId;

    @Column(name = "from_bin_id", nullable = false, updatable = false)
    private long fromBinId;

    @Column(name = "to_warehouse_id", nullable = false, updatable = false)
    private long toWarehouseId;

    @Column(name = "to_bin_id")
    private Long toBinId;

    protected StockTransferLine() {
        // for JPA
    }

    StockTransferLine(StockTransfer transfer, int lineNo, long productId, long fromBinId, long quantity) {
        this.transfer = transfer;
        this.lineNo = lineNo;
        this.productId = productId;
        this.fromBinId = fromBinId;
        this.quantity = quantity;
        this.fromWarehouseId = transfer.getFromWarehouseId();
        this.toWarehouseId = transfer.getToWarehouseId();
    }

    void arriveIn(long binId) {
        this.toBinId = binId;
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

    public long getQuantity() {
        return quantity;
    }

    public long getFromBinId() {
        return fromBinId;
    }

    public Long getToBinId() {
        return toBinId;
    }
}
