package com.minierp.reporting;

import com.minierp.invoicing.InvoiceDtos;
import com.minierp.shared.ErpProperties;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/** Renders an issued invoice as a one-document A4 PDF. */
@Component
class InvoicePdf {

    private static final String[] COLUMNS = {"#", "Description", "Qty", "Unit", "Unit price", "Tax", "Net", "Tax amount"};
    private static final float[] WIDTHS = {4, 36, 8, 6, 12, 7, 13, 13};

    private final ErpProperties props;

    InvoicePdf(ErpProperties props) {
        this.props = props;
    }

    byte[] render(InvoiceDtos.InvoiceResponse invoice) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 48, 48, 48, 60);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        writer.setPageEvent(new Pdfs.Footer("Invoice " + invoice.invoiceNumber()));
        document.addTitle("Invoice " + invoice.invoiceNumber());
        document.addCreator("Mini ERP");
        document.open();

        // Seller on the left, invoice facts on the right.
        PdfPTable top = new PdfPTable(new float[] {55, 45});
        top.setWidthPercentage(100);
        ErpProperties.Company company = props.company();
        top.addCell(block(company.name() + "\n" + company.address() + "\nTax ID: " + company.taxId()));
        top.addCell(block("INVOICE " + invoice.invoiceNumber()
                + "\nIssue date: " + invoice.issueDate()
                + "\nDue date: " + invoice.dueDate()
                + "\nCurrency: " + invoice.currency()));
        document.add(top);

        document.add(new Paragraph(" "));
        document.add(new Paragraph("Bill to", Pdfs.BODY_BOLD));
        document.add(new Paragraph(invoice.billToName() + "\n" + invoice.billToAddress(), Pdfs.BODY));
        document.add(new Paragraph(" "));

        PdfPTable lines = new PdfPTable(WIDTHS);
        lines.setWidthPercentage(100);
        lines.setHeaderRows(1);
        for (String column : COLUMNS) {
            lines.addCell(Pdfs.header(column));
        }
        for (InvoiceDtos.InvoiceLineResponse line : invoice.lines()) {
            lines.addCell(Pdfs.number(Integer.toString(line.lineNo()), null));
            lines.addCell(Pdfs.text(line.description(), null));
            lines.addCell(Pdfs.number(line.quantity().stripTrailingZeros().toPlainString(), null));
            lines.addCell(Pdfs.text(line.uom(), null));
            lines.addCell(Pdfs.number(line.unitPrice().formatted(), null));
            lines.addCell(Pdfs.number(percent(line.taxRateBp()), null));
            lines.addCell(Pdfs.number(line.net().formatted(), null));
            lines.addCell(Pdfs.number(line.tax().formatted(), null));
        }
        document.add(lines);

        PdfPTable totals = new PdfPTable(new float[] {70, 30});
        totals.setWidthPercentage(45);
        totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totals.setSpacingBefore(10);
        totalRow(totals, "Net total", invoice.netTotal().formatted(), false);
        totalRow(totals, "Tax", invoice.taxTotal().formatted(), false);
        totalRow(totals, "Total due (" + invoice.currency() + ")", invoice.grossTotal().formatted(), true);
        document.add(totals);

        document.add(new Paragraph(" "));
        document.add(new Paragraph("Please pay by " + invoice.dueDate() + ", quoting " + invoice.invoiceNumber() + ".",
                Pdfs.SUBTITLE));
        document.close();
        return out.toByteArray();
    }

    private static PdfPCell block(String text) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, Pdfs.BODY));
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private static void totalRow(PdfPTable table, String label, String amount, boolean bold) {
        table.addCell(Pdfs.cell(label, bold ? Pdfs.BODY_BOLD : Pdfs.BODY, Element.ALIGN_LEFT, null));
        table.addCell(Pdfs.cell(amount, bold ? Pdfs.BODY_BOLD : Pdfs.BODY, Element.ALIGN_RIGHT, null));
    }

    /** 2000 basis points as "20%", 550 as "5.5%". */
    static String percent(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + "%";
    }
}
