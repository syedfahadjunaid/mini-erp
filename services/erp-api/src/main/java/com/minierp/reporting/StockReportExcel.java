package com.minierp.reporting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link StockReport} as an .xlsx workbook: a data sheet with a frozen, filterable
 * header row and real numbers, plus an "About" sheet saying what the numbers mean.
 */
@Component
class StockReportExcel {

    private static final String[] COLUMNS = {
        "Warehouse", "SKU", "Product", "Unit", "On hand", "Reserved", "Available", "Reorder point", "Reorder quantity", "Status"};

    byte[] render(StockReport report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Workbooks.Styles styles = Workbooks.Styles.of(workbook);
            Sheet sheet = workbook.createSheet("Stock");

            Row header = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(COLUMNS[i]);
                cell.setCellStyle(styles.header());
            }
            int rowIndex = 1;
            for (StockReport.Row line : report.rows()) {
                Row row = sheet.createRow(rowIndex++);
                text(row, 0, line.warehouseCode(), styles);
                text(row, 1, line.sku(), styles);
                text(row, 2, line.productName(), styles);
                text(row, 3, line.baseUom(), styles);
                number(row, 4, line.onHand(), styles);
                number(row, 5, line.reserved(), styles);
                number(row, 6, line.available(), styles);
                if (line.reorderPoint() != null) {
                    number(row, 7, line.reorderPoint(), styles);
                    number(row, 8, line.reorderQuantity(), styles);
                }
                Cell status = row.createCell(9);
                status.setCellValue(StockReportPdf.statusText(line.status()));
                status.setCellStyle(line.status() == StockReport.Status.LOW ? styles.lowStock() : styles.text());
            }

            sheet.createFreezePane(0, 1); // header stays visible while scrolling
            if (!report.rows().isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, report.rows().size(), 0, COLUMNS.length - 1));
            }
            Workbooks.fitColumns(sheet, COLUMNS.length);

            Sheet about = workbook.createSheet("About");
            String[][] notes = {
                {"Report", "Stock report"},
                {"Generated (UTC)", report.generatedAt().atOffset(ZoneOffset.UTC).toLocalDateTime().toString()},
                {"Scope", report.asOf() == null ? "Current stock"
                        : "As of " + report.asOf() + " (rebuilt from the stock ledger)"},
                {"Warehouses", report.warehouse()},
                {"Quantities", "In each product's base unit (column Unit)."},
                {"Available", "On hand minus reserved: what can still be promised to customers."},
                {"Status LOW", "Available is at or below the reorder point."},
            };
            for (int i = 0; i < notes.length; i++) {
                Row row = about.createRow(i);
                row.createCell(0).setCellValue(notes[i][0]);
                row.createCell(1).setCellValue(notes[i][1]);
            }
            Workbooks.fitColumns(about, 2);
            return Workbooks.toBytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void text(Row row, int column, String value, Workbooks.Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.text());
    }

    private static void number(Row row, int column, long value, Workbooks.Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.integer());
    }
}
