package com.minierp.reporting;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/** Renders a {@link StockReport} as a landscape A4 PDF table; low-stock rows are tinted red. */
@Component
class StockReportPdf {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneId.of("UTC"));
    private static final String[] COLUMNS = {
        "Warehouse", "SKU", "Product", "Unit", "On hand", "Reserved", "Available", "Reorder point", "Status"};
    private static final float[] WIDTHS = {14, 15, 25, 6, 9, 9, 9, 8, 6};

    byte[] render(StockReport report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 48);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        writer.setPageEvent(new Pdfs.Footer("Stock report"));
        document.addTitle("Stock report");
        document.addCreator("Mini ERP");
        document.open();

        document.add(new Paragraph("Stock report", Pdfs.TITLE));
        String scope = report.asOf() == null
                ? "Current stock"
                : "Stock as of " + TIMESTAMP.format(report.asOf()) + " (rebuilt from the stock ledger)";
        document.add(new Paragraph(scope + "  |  " + report.warehouse() + "  |  generated " + TIMESTAMP.format(report.generatedAt())
                + "  |  " + report.rows().size() + " lines, " + report.lowCount() + " at or below reorder point", Pdfs.SUBTITLE));
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(WIDTHS);
        table.setWidthPercentage(100);
        table.setHeaderRows(1); // repeat the header on every page
        for (String column : COLUMNS) {
            table.addCell(Pdfs.header(column));
        }
        int index = 0;
        for (StockReport.Row row : report.rows()) {
            Color background = row.status() == StockReport.Status.LOW ? Pdfs.LOW_STOCK_BACKGROUND
                    : index++ % 2 == 1 ? Pdfs.ZEBRA : null;
            table.addCell(Pdfs.text(row.warehouseCode(), background));
            table.addCell(Pdfs.text(row.sku(), background));
            table.addCell(Pdfs.text(row.productName(), background));
            table.addCell(Pdfs.text(row.baseUom(), background));
            table.addCell(Pdfs.number(Long.toString(row.onHand()), background));
            table.addCell(Pdfs.number(Long.toString(row.reserved()), background));
            table.addCell(Pdfs.cell(Long.toString(row.available()), Pdfs.BODY_BOLD, Element.ALIGN_RIGHT, background));
            table.addCell(Pdfs.number(row.reorderPoint() == null ? "-" : row.reorderPoint().toString(), background));
            table.addCell(Pdfs.text(statusText(row.status()), background));
        }
        if (report.rows().isEmpty()) {
            table.addCell(Pdfs.text("No stock.", null));
            table.completeRow();
        }
        document.add(table);
        document.close();
        return out.toByteArray();
    }

    static String statusText(StockReport.Status status) {
        return switch (status) {
            case OK -> "OK";
            case LOW -> "LOW";
            case NO_RULE -> "-";
        };
    }
}
