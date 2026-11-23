package com.minierp.invoicing;

import com.minierp.catalog.CatalogService;
import com.minierp.partners.PartnerService;
import com.minierp.sales.SalesQueries;
import com.minierp.shared.Actor;
import com.minierp.shared.BusinessCalendar;
import com.minierp.shared.Errors;
import com.minierp.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues invoices from shipments: one invoice per shipment, billing exactly what was shipped at
 * the order's prices, numbered INV-{year}-{n} without gaps.
 *
 * <p>{@link #issueForShipment} is idempotent: asking again for an invoiced shipment returns the
 * existing invoice. That matters because it is called from an event listener, and events are
 * delivered at least once.
 */
@Service
@Transactional(readOnly = true)
public class InvoiceService {

    /** @param created false if the shipment had been invoiced already */
    public record Issued(InvoiceDtos.InvoiceResponse invoice, boolean created) {}

    private final InvoiceRepository invoices;
    private final InvoiceNumbering numbering;
    private final SalesQueries sales;
    private final PartnerService partners;
    private final CatalogService catalog;
    private final BusinessCalendar calendar;

    InvoiceService(InvoiceRepository invoices, InvoiceNumbering numbering, SalesQueries sales, PartnerService partners,
            CatalogService catalog, BusinessCalendar calendar) {
        this.invoices = invoices;
        this.numbering = numbering;
        this.sales = sales;
        this.partners = partners;
        this.catalog = catalog;
        this.calendar = calendar;
    }

    @Transactional
    public Issued issueForShipment(long shipmentId, Actor actor) {
        Optional<Invoice> existing = invoices.findByShipmentId(shipmentId);
        if (existing.isPresent()) {
            return new Issued(toResponse(existing.get()), false);
        }
        SalesQueries.ShipmentForInvoice shipment = sales.shipmentForInvoice(shipmentId); // 404 if there's no such shipment
        PartnerService.CustomerInfo customer = partners.customer(shipment.customerId());

        // The issue date (and so the fiscal year) is today in the company's time zone.
        LocalDate issueDate = calendar.today();
        int year = issueDate.getYear();
        numbering.lockYear(year);
        // Re-check under the lock: a concurrent call (say, the event listener and a user) may
        // have invoiced this shipment while we waited. Nothing has been drawn yet, so returning
        // here leaves no gap.
        existing = invoices.findByShipmentId(shipmentId);
        if (existing.isPresent()) {
            return new Issued(toResponse(existing.get()), false);
        }
        int sequence = numbering.next(year);

        Invoice invoice = new Invoice(new Invoice.Header(year, sequence, shipment.shipmentId(), shipment.salesOrderId(),
                customer.id(), shipment.currency(), issueDate, issueDate.plusDays(customer.paymentTermsDays()),
                customer.name(), customer.address().asLines(), actor.username(), calendar.now()));
        Map<Long, CatalogService.ProductInfo> products = catalog.products(
                shipment.lines().stream().map(SalesQueries.ShipmentForInvoice.Line::productId).toList());
        for (SalesQueries.ShipmentForInvoice.Line line : shipment.lines()) {
            CatalogService.ProductInfo product = products.get(line.productId());
            long net = Money.lineAmount(line.quantity(), line.unitPriceMinor());
            long tax = Money.taxOn(net, line.taxRateBp());
            invoice.addLine(line.shipmentLineId(), line.productId(), product.sku() + " " + product.name(), line.uom(),
                    line.quantity(), line.unitPriceMinor(), line.taxRateBp(), net, tax);
        }
        invoices.save(invoice);
        return new Issued(toResponse(invoice), true);
    }

    public InvoiceDtos.InvoiceResponse get(long id) {
        return toResponse(invoices.findById(id).orElseThrow(() -> new Errors.NotFoundException("Invoice", id)));
    }

    public Optional<InvoiceDtos.InvoiceResponse> forShipment(long shipmentId) {
        return invoices.findByShipmentId(shipmentId).map(InvoiceService::toResponse);
    }

    public List<InvoiceDtos.InvoiceResponse> recent() {
        return invoices.findTop100ByOrderByIdDesc().stream().map(InvoiceService::toResponse).toList();
    }

    public List<InvoiceDtos.InvoiceResponse> forOrder(long salesOrderId) {
        return invoices.findBySalesOrderIdOrderByIdAsc(salesOrderId).stream().map(InvoiceService::toResponse).toList();
    }

    static InvoiceDtos.InvoiceResponse toResponse(Invoice i) {
        String ccy = i.getCurrency();
        List<InvoiceDtos.InvoiceLineResponse> lines = i.getLines().stream()
                .map(l -> new InvoiceDtos.InvoiceLineResponse(l.getLineNo(), l.getShipmentLineId(), l.getProductId(),
                        l.getDescription(), l.getUom(), l.getQuantity(), Money.of(l.getUnitPriceMinor(), ccy), l.getTaxRateBp(),
                        Money.of(l.getNetMinor(), ccy), Money.of(l.getTaxMinor(), ccy)))
                .toList();
        return new InvoiceDtos.InvoiceResponse(i.getId(), i.getInvoiceNumber(), i.getFiscalYear(), i.getSequenceNumber(),
                i.getShipmentId(), i.getSalesOrderId(), i.getCustomerId(), ccy, i.getIssueDate(), i.getDueDate(),
                i.getBillToName(), i.getBillToAddress(), Money.of(i.getNetTotalMinor(), ccy), Money.of(i.getTaxTotalMinor(), ccy),
                Money.of(i.getGrossTotalMinor(), ccy), i.getIssuedBy(), i.getIssuedAt(), lines);
    }
}
