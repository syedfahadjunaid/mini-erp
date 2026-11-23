package com.minierp.inventory;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Moving stock from one warehouse to another. An aggregate: the transfer owns its lines
 * ({@code cascade = ALL}), and they are only changed through it.
 */
@Entity
@Table(name = "stock_transfers")
public class StockTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_number", nullable = false, updatable = false)
    private String transferNumber;

    @Column(name = "from_warehouse_id", nullable = false, updatable = false)
    private long fromWarehouseId;

    @Column(name = "to_warehouse_id", nullable = false, updatable = false)
    private long toWarehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "received_by")
    private String receivedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL)
    @OrderBy("lineNo")
    private List<StockTransferLine> lines = new ArrayList<>();

    protected StockTransfer() {
        // for JPA
    }

    public StockTransfer(String transferNumber, long fromWarehouseId, long toWarehouseId, String createdBy, Instant createdAt) {
        this.transferNumber = transferNumber;
        this.fromWarehouseId = fromWarehouseId;
        this.toWarehouseId = toWarehouseId;
        this.status = TransferStatus.IN_TRANSIT;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public StockTransferLine addLine(long productId, long fromBinId, long quantity) {
        StockTransferLine line = new StockTransferLine(this, lines.size() + 1, productId, fromBinId, quantity);
        lines.add(line);
        return line;
    }

    /** Arrival: every line goes into {@code toBinId}, and the transfer is done. */
    public void receive(long toBinId, String actor, Instant at) {
        status = status.after(TransferStatus.Action.RECEIVE);
        for (StockTransferLine line : lines) {
            line.arriveIn(toBinId);
        }
        receivedBy = actor;
        receivedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getTransferNumber() {
        return transferNumber;
    }

    public long getFromWarehouseId() {
        return fromWarehouseId;
    }

    public long getToWarehouseId() {
        return toWarehouseId;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public List<StockTransferLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
