package com.minierp.invoicing;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.Immutable;

/**
 * An issued invoice. Immutable: {@link Immutable} for Hibernate, and the database rejects
 * UPDATE and DELETE. Totals are sums of the lines' rounded amounts, so they always add up.
 */
@Entity
@Immutable
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false)
    private String invoiceNumber;

    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "shipment_id", nullable = false)
    private long shipmentId;

    @Column(name = "sales_order_id", nullable = false)
    private long salesOrderId;

    @Column(name = "customer_id", nullable = false)
    private long customerId;

    @Column(nullable = false)
    private String currency;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "bill_to_name", nullable = false)
    private String billToName;

    @Column(name = "bill_to_address", nullable = false)
    private String billToAddress;

    @Column(name = "net_total_minor", nullable = false)
    private long netTotalMinor;

    @Column(name = "tax_total_minor", nullable = false)
    private long taxTotalMinor;

    @Column(name = "gross_total_minor", nullable = false)
    private long grossTotalMinor;

    @Column(name = "issued_by", nullable = false)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<InvoiceLine> lines = new ArrayList<>();

    protected Invoice() {
        // for JPA
    }

    /** Header data that comes from the shipment, the customer and the clock. */
    record Header(int fiscalYear, int sequenceNumber, long shipmentId, long salesOrderId, long customerId, String currency,
            LocalDate issueDate, LocalDate dueDate, String billToName, String billToAddress, String issuedBy, Instant issuedAt) {}

    Invoice(Header h) {
        this.fiscalYear = h.fiscalYear();
        this.sequenceNumber = h.sequenceNumber();
        this.invoiceNumber = format(h.fiscalYear(), h.sequenceNumber());
        this.shipmentId = h.shipmentId();
        this.salesOrderId = h.salesOrderId();
        this.customerId = h.customerId();
        this.currency = h.currency();
        this.issueDate = h.issueDate();
        this.dueDate = h.dueDate();
        this.billToName = h.billToName();
        this.billToAddress = h.billToAddress();
        this.issuedBy = h.issuedBy();
        this.issuedAt = h.issuedAt();
    }

    /** INV-2026-000042: the same format the database CHECK constraint expects. */
    static String format(int fiscalYear, int sequenceNumber) {
        return "INV-%d-%06d".formatted(fiscalYear, sequenceNumber);
    }

    void addLine(long shipmentLineId, long productId, String description, String uom, BigDecimal quantity, long unitPriceMinor,
            int taxRateBp, long netMinor, long taxMinor) {
        lines.add(new InvoiceLine(this, lines.size() + 1, shipmentLineId, productId, description, uom, quantity, unitPriceMinor,
                taxRateBp, netMinor, taxMinor));
        netTotalMinor = Math.addExact(netTotalMinor, netMinor);
        taxTotalMinor = Math.addExact(taxTotalMinor, taxMinor);
        grossTotalMinor = Math.addExact(netTotalMinor, taxTotalMinor);
    }

    public Long getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public int getFiscalYear() {
        return fiscalYear;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getShipmentId() {
        return shipmentId;
    }

    public long getSalesOrderId() {
        return salesOrderId;
    }

    public long getCustomerId() {
        return customerId;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getBillToName() {
        return billToName;
    }

    public String getBillToAddress() {
        return billToAddress;
    }

    public long getNetTotalMinor() {
        return netTotalMinor;
    }

    public long getTaxTotalMinor() {
        return taxTotalMinor;
    }

    public long getGrossTotalMinor() {
        return grossTotalMinor;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public List<InvoiceLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
