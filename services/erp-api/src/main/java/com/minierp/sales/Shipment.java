package com.minierp.sales;

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

/** Goods that left the warehouse for a customer. Written once; invoiced exactly once. */
@Entity
@Immutable
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_number", nullable = false)
    private String shipmentNumber;

    @Column(name = "sales_order_id", nullable = false)
    private long salesOrderId;

    @Column(name = "warehouse_id", nullable = false)
    private long warehouseId;

    @Column(name = "shipped_by", nullable = false)
    private String shippedBy;

    @Column(name = "shipped_at", nullable = false)
    private Instant shippedAt;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<ShipmentLine> lines = new ArrayList<>();

    protected Shipment() {
        // for JPA
    }

    Shipment(String shipmentNumber, long salesOrderId, long warehouseId, String shippedBy, Instant shippedAt) {
        this.shipmentNumber = shipmentNumber;
        this.salesOrderId = salesOrderId;
        this.warehouseId = warehouseId;
        this.shippedBy = shippedBy;
        this.shippedAt = shippedAt;
    }

    ShipmentLine addLine(SalesOrderLine orderLine, BigDecimal quantity, long baseQuantity) {
        ShipmentLine line = new ShipmentLine(this, orderLine.getId(), orderLine.getProductId(), quantity, baseQuantity);
        lines.add(line);
        return line;
    }

    public Long getId() {
        return id;
    }

    public String getShipmentNumber() {
        return shipmentNumber;
    }

    public long getSalesOrderId() {
        return salesOrderId;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public String getShippedBy() {
        return shippedBy;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public List<ShipmentLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
