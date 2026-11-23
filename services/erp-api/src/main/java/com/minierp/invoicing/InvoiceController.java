package com.minierp.invoicing;

import com.minierp.shared.Actor;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Invoices: FINANCE issues them; FINANCE, SALES and MANAGER can read them. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('FINANCE', 'SALES', 'MANAGER')")
class InvoiceController {

    private final InvoiceService invoices;

    InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @GetMapping("/invoices")
    List<InvoiceDtos.InvoiceResponse> list(@RequestParam(required = false) Long salesOrderId) {
        return salesOrderId == null ? invoices.recent() : invoices.forOrder(salesOrderId);
    }

    @GetMapping("/invoices/{id}")
    InvoiceDtos.InvoiceResponse get(@PathVariable long id) {
        return invoices.get(id);
    }

    /**
     * Invoice a shipment now (normally the event listener already did). 201 if a new invoice
     * was issued, 200 with the existing one if the shipment was invoiced already.
     */
    @PostMapping("/shipments/{shipmentId}/invoice")
    @PreAuthorize("hasRole('FINANCE')")
    ResponseEntity<InvoiceDtos.InvoiceResponse> issue(@PathVariable long shipmentId, Actor actor) {
        InvoiceService.Issued issued = invoices.issueForShipment(shipmentId, actor);
        return ResponseEntity.status(issued.created() ? HttpStatus.CREATED : HttpStatus.OK).body(issued.invoice());
    }

    @GetMapping("/shipments/{shipmentId}/invoice")
    ResponseEntity<InvoiceDtos.InvoiceResponse> forShipment(@PathVariable long shipmentId) {
        return ResponseEntity.of(invoices.forShipment(shipmentId));
    }
}
