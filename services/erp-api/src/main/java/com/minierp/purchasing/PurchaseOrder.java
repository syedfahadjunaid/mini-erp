package com.minierp.purchasing;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A purchase order: an aggregate of header + lines. Other modules' records (supplier,
 * warehouse, products) are referenced by id only, never as JPA associations, so the modules
 * stay independent.
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private long supplierId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private long warehouseId;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseOrderStatus status;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    private String notes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Optimistic locking: an edit based on a stale copy fails instead of overwriting newer data. */
    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
        // for JPA
    }

    public PurchaseOrder(String orderNumber, long supplierId, long warehouseId, String currency, LocalDate expectedDate,
            String notes, String createdBy, Instant createdAt) {
        this.orderNumber = orderNumber;
        this.supplierId = supplierId;
        this.warehouseId = warehouseId;
        this.currency = currency;
        this.status = PurchaseOrderStatus.DRAFT;
        this.expectedDate = expectedDate;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    void addLine(long productId, String uom, BigDecimal quantity, long baseQuantity, long unitPriceMinor, long lineTotalMinor) {
        lines.add(new PurchaseOrderLine(this, lines.size() + 1, productId, uom, quantity, baseQuantity, unitPriceMinor,
                lineTotalMinor));
    }

    /** Draft edits replace all lines (orphanRemoval deletes the old ones). */
    void clearLines() {
        lines.clear();
    }

    void updateHeader(LocalDate expectedDate, String notes) {
        this.expectedDate = expectedDate;
        this.notes = notes;
    }

    /** Applies a user action; the state machine throws a 409 if the status doesn't allow it. */
    PurchaseOrderStatus apply(PurchaseOrderAction action, String actor, Instant at) {
        PurchaseOrderStatus previous = status;
        status = status.after(action);
        switch (action) {
            case APPROVE -> {
                approvedBy = actor;
                approvedAt = at;
            }
            case SUBMIT, REJECT, CANCEL, CLOSE -> {
                // no extra fields
            }
        }
        return previous;
    }

    /** After a goods receipt was booked on the lines. */
    PurchaseOrderStatus afterReceipt() {
        PurchaseOrderStatus previous = status;
        status = status.afterReceipt(lines.stream().allMatch(PurchaseOrderLine::isFullyReceived));
        return previous;
    }

    public long totalMinor() {
        long total = 0;
        for (PurchaseOrderLine line : lines) {
            total = Math.addExact(total, line.getLineTotalMinor());
        }
        return total;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public long getSupplierId() {
        return supplierId;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public String getCurrency() {
        return currency;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public LocalDate getExpectedDate() {
        return expectedDate;
    }

    public String getNotes() {
        return notes;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<PurchaseOrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
