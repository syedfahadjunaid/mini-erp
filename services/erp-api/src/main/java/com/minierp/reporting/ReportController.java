package com.minierp.reporting;

import com.minierp.invoicing.InvoiceService;
import com.minierp.shared.BusinessCalendar;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** File exports (PDF, Excel) and the stock reconciliation. */
@RestController
@RequestMapping("/api")
class ReportController {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);

    private final StockReportService stockReports;
    private final StockReportPdf stockPdf;
    private final StockReportExcel stockExcel;
    private final MovementsExcel movementsExcel;
    private final InvoicePdf invoicePdf;
    private final InvoiceService invoices;
    private final ReconciliationService reconciliation;
    private final BusinessCalendar calendar;

    ReportController(StockReportService stockReports, StockReportPdf stockPdf, StockReportExcel stockExcel,
            MovementsExcel movementsExcel, InvoicePdf invoicePdf, InvoiceService invoices, ReconciliationService reconciliation,
            BusinessCalendar calendar) {
        this.stockReports = stockReports;
        this.stockPdf = stockPdf;
        this.stockExcel = stockExcel;
        this.movementsExcel = movementsExcel;
        this.invoicePdf = invoicePdf;
        this.invoices = invoices;
        this.reconciliation = reconciliation;
        this.calendar = calendar;
    }

    /** Current stock, or stock as of {@code asOf} (rebuilt from the ledger), as PDF. */
    @GetMapping("/reports/stock.pdf")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'MANAGER', 'FINANCE')")
    ResponseEntity<byte[]> stockPdf(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Instant asOf) {
        return file(stockPdf.render(stockReports.build(warehouseId, asOf)), MediaType.APPLICATION_PDF, "stock-report", "pdf");
    }

    @GetMapping("/reports/stock.xlsx")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'MANAGER', 'FINANCE')")
    ResponseEntity<byte[]> stockXlsx(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Instant asOf) {
        return file(stockExcel.render(stockReports.build(warehouseId, asOf)), XLSX, "stock-report", "xlsx");
    }

    /** The ledger itself, for auditors: {@code from} inclusive, {@code to} exclusive. */
    @GetMapping("/reports/stock-movements.xlsx")
    @PreAuthorize("hasAnyRole('MANAGER', 'FINANCE')")
    ResponseEntity<byte[]> movementsXlsx(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return file(movementsExcel.render(warehouseId, from, to), XLSX, "stock-movements", "xlsx");
    }

    @GetMapping("/invoices/{id}/pdf")
    @PreAuthorize("hasAnyRole('FINANCE', 'SALES', 'MANAGER')")
    ResponseEntity<byte[]> invoicePdf(@PathVariable long id) {
        var invoice = invoices.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(invoice.invoiceNumber() + ".pdf").build().toString())
                .body(invoicePdf.render(invoice));
    }

    @GetMapping("/reports/reconciliation")
    @PreAuthorize("hasAnyRole('MANAGER', 'FINANCE')")
    ReconciliationService.Report reconcile() {
        return reconciliation.run();
    }

    private ResponseEntity<byte[]> file(byte[] content, MediaType type, String name, String extension) {
        String filename = name + "-" + FILE_STAMP.format(calendar.now()) + "." + extension;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(content);
    }
}
