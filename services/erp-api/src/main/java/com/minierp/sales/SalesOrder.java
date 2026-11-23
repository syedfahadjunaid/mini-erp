package com.minierp.sales;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A customer's order: header + lines, one fulfilment warehouse, the customer's currency. */
@Entity
@Table(name = "sales_orders")
public class SalesOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private long warehouseId;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SalesOrderStatus status;

    private String notes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<SalesOrderLine> lines = new ArrayList<>();

    protected SalesOrder() {
        // for JPA
    }

    public SalesOrder(String orderNumber, long customerId, long warehouseId, String currency, String notes, String createdBy,
            Instant createdAt) {
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.warehouseId = warehouseId;
        this.currency = currency;
        this.status = SalesOrderStatus.DRAFT;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    void addLine(long productId, String uom, BigDecimal quantity, long baseQuantity, long unitPriceMinor, int taxRateBp,
            long lineNetMinor) {
        lines.add(new SalesOrderLine(this, lines.size() + 1, productId, uom, quantity, baseQuantity, unitPriceMinor,
                taxRateBp, lineNetMinor));
    }

    void clearLines() {
        lines.clear();
    }

    void updateNotes(String notes) {
        this.notes = notes;
    }

    SalesOrderStatus apply(SalesOrderAction action, String actor, Instant at) {
        SalesOrderStatus previous = status;
        status = status.after(action);
        switch (action) {
            case CONFIRM -> {
                confirmedBy = actor;
                confirmedAt = at;
            }
            case CANCEL, CLOSE -> {
                // no extra fields
            }
        }
        return previous;
    }

    SalesOrderStatus afterShipment() {
        SalesOrderStatus previous = status;
        status = status.afterShipment(lines.stream().allMatch(SalesOrderLine::isFullyShipped));
        return previous;
    }

    public long netTotalMinor() {
        long total = 0;
        for (SalesOrderLine line : lines) {
            total = Math.addExact(total, line.getLineNetMinor());
        }
        return total;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public long getCustomerId() {
        return customerId;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public String getCurrency() {
        return currency;
    }

    public SalesOrderStatus getStatus() {
        return status;
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

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<SalesOrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
