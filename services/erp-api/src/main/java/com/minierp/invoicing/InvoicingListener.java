package com.minierp.invoicing;

import com.minierp.sales.ShipmentDispatched;
import com.minierp.shared.Actor;
import com.minierp.shared.ErpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Invoices every shipment automatically (when {@code erp.invoicing.auto-issue} is on).
 *
 * <p>{@link ApplicationModuleListener} = run asynchronously, after the shipment's transaction
 * committed, in a new transaction of its own. The event was stored in the same transaction as
 * the shipment, so it survives a crash; if this method throws, the publication stays
 * incomplete and is retried later (see EventPublicationMaintenance). Issuing is idempotent, so
 * a retry can't produce a second invoice.
 *
 * <p>Note the direction: sales knows nothing about invoicing. Invoicing depends on sales (it
 * listens to its event and reads its data), never the other way round.
 */
@Component
class InvoicingListener {

    private static final Logger log = LoggerFactory.getLogger(InvoicingListener.class);

    private final InvoiceService invoices;
    private final ErpProperties props;

    InvoicingListener(InvoiceService invoices, ErpProperties props) {
        this.invoices = invoices;
        this.props = props;
    }

    @ApplicationModuleListener
    public void on(ShipmentDispatched event) {
        if (!props.invoicing().autoIssue()) {
            return;
        }
        InvoiceService.Issued issued = invoices.issueForShipment(event.shipmentId(), Actor.SYSTEM);
        if (issued.created()) {
            log.info("Issued {} for shipment {}", issued.invoice().invoiceNumber(), event.shipmentNumber());
        }
    }
}
