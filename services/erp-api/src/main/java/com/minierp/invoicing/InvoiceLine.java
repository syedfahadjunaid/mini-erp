package com.minierp.invoicing;

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

/** One billed shipment line: quantity, net price, tax. Amounts rounded once, half up. */
@Entity
@Immutable
@Table(name = "invoice_lines")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "shipment_id", nullable = false)
    private long shipmentId;

    @Column(name = "shipment_line_id", nullable = false)
    private long shipmentLineId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private String description;

    @Column(name = "uom_code", nullable = false)
    private String uom;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price_minor", nullable = false)
    private long unitPriceMinor;

    @Column(name = "tax_rate_bp", nullable = false)
    private int taxRateBp;

    @Column(name = "net_minor", nullable = false)
    private long netMinor;

    @Column(name = "tax_minor", nullable = false)
    private long taxMinor;

    protected InvoiceLine() {
        // for JPA
    }

    InvoiceLine(Invoice invoice, int lineNo, long shipmentLineId, long productId, String description, String uom,
            BigDecimal quantity, long unitPriceMinor, int taxRateBp, long netMinor, long taxMinor) {
        this.invoice = invoice;
        this.shipmentId = invoice.getShipmentId();
        this.lineNo = lineNo;
        this.shipmentLineId = shipmentLineId;
        this.productId = productId;
        this.description = description;
        this.uom = uom;
        this.quantity = quantity;
        this.unitPriceMinor = unitPriceMinor;
        this.taxRateBp = taxRateBp;
        this.netMinor = netMinor;
        this.taxMinor = taxMinor;
    }

    public int getLineNo() {
        return lineNo;
    }

    public long getShipmentLineId() {
        return shipmentLineId;
    }

    public long getProductId() {
        return productId;
    }

    public String getDescription() {
        return description;
    }

    public String getUom() {
        return uom;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public long getUnitPriceMinor() {
        return unitPriceMinor;
    }

    public int getTaxRateBp() {
        return taxRateBp;
    }

    public long getNetMinor() {
        return netMinor;
    }

    public long getTaxMinor() {
        return taxMinor;
    }
}
