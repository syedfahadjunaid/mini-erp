package com.minierp.invoicing;

import com.minierp.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response bodies of the invoicing API (also what the PDF renderer prints). */
public final class InvoiceDtos {

    private InvoiceDtos() {}

    public record InvoiceLineResponse(int lineNo, long shipmentLineId, long productId, String description, String uom,
            BigDecimal quantity, Money unitPrice, int taxRateBp, Money net, Money tax) {}

    public record InvoiceResponse(long id, String invoiceNumber, int fiscalYear, int sequenceNumber, long shipmentId,
            long salesOrderId, long customerId, String currency, LocalDate issueDate, LocalDate dueDate, String billToName,
            String billToAddress, Money netTotal, Money taxTotal, Money grossTotal, String issuedBy, Instant issuedAt,
            List<InvoiceLineResponse> lines) {}
}
