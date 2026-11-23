package com.minierp.reporting;

import java.awt.Color;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Shared look for the PDFs (OpenPDF): fonts, table cells, and a footer with page numbers.
 * The standard Helvetica font is built into every PDF reader, so nothing has to be embedded.
 */
final class Pdfs {

    static final Color HEADER_BACKGROUND = new Color(0x1F, 0x3A, 0x5F);
    static final Color LOW_STOCK_BACKGROUND = new Color(0xFD, 0xE2, 0xE1);
    static final Color ZEBRA = new Color(0xF4, 0xF6, 0xF8);

    static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);
    static final Font SUBTITLE = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.DARK_GRAY);
    static final Font HEADER = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
    static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL);
    static final Font BODY_BOLD = new Font(Font.HELVETICA, 9, Font.BOLD);

    private Pdfs() {}

    static PdfPCell header(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER));
        cell.setBackgroundColor(HEADER_BACKGROUND);
        cell.setPadding(5);
        cell.setBorderColor(Color.WHITE);
        return cell;
    }

    static PdfPCell text(String text, Color background) {
        return cell(text, BODY, Element.ALIGN_LEFT, background);
    }

    /** Numbers are right-aligned, so digits line up. */
    static PdfPCell number(String text, Color background) {
        return cell(text, BODY, Element.ALIGN_RIGHT, background);
    }

    static PdfPCell cell(String text, Font font, int alignment, Color background) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(Color.LIGHT_GRAY);
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        return cell;
    }

    /** "Mini ERP - <label> - page 3", at the bottom of every page. */
    static final class Footer extends PdfPageEventHelper {
        private final String label;

        Footer(String label) {
            this.label = label;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Phrase footer = new Phrase("Mini ERP - " + label + " - page " + writer.getPageNumber(), SUBTITLE);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT, footer,
                    document.right(), document.bottom() - 18, 0);
        }
    }
}
